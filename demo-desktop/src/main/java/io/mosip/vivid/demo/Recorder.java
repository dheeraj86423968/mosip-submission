package io.mosip.vivid.demo;

import io.mosip.vivid.api.SessionState;
import io.mosip.vivid.api.SessionState.Phase;
import io.mosip.vivid.api.Workflow;
import io.mosip.vivid.device.mock.Pacing;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Headless recorder: runs scripted attack-lab sessions through the real pipeline, paints the same {@link LabView}
 * offscreen after each frame, and writes key-state PNG screenshots plus a Motion-JPEG AVI. Sessions run on the frame
 * clock (see {@link DemoClock}), so the video plays at real session speed however fast the machine is.
 */
public final class Recorder {
    /** A switch of subject at a session time. */
    public record Switch(long atMs, Subject subject) {}

    /** One scripted session. {@code shots} maps a key state (passive, challenge, result) to a PNG file name. */
    public record Take(String title, String subtitle, Workflow workflow, Subject subject, long seed, List<Switch> switches,
                       Map<String, String> shots) {}

    /** Outcome of one take. */
    public record TakeResult(Take take, SessionState finalState, String decision, long sessionMs, String evidenceHash) {}

    public static final long MAX_SESSION_MS = 75_000;
    private static final long PNG_LIMIT = 300 * 1024;

    private final DemoEngine engine;
    private final AtomicBoolean injection;
    private final Path out;
    private final double fps;
    private final long holdMs;
    private final long titleMs;
    private final PrintStream log;
    private int videoWidth = 1024;
    private float quality = 0.55f;
    private final List<Path> written = new ArrayList<>();

    /**
     * @param injection flag shared with {@link DemoEngine}: true while the injected stream is in front of the camera
     */
    public Recorder(DemoEngine engine, AtomicBoolean injection, Path out, double fps, long holdMs, long titleMs, PrintStream log) {
        this.engine = engine; this.injection = injection; this.out = out; this.fps = fps; this.holdMs = holdMs; this.titleMs = titleMs; this.log = log;
    }

    public List<Path> written() { return written; }

    /** Video frame width (height follows the 16:9 view) and JPEG quality. */
    public Recorder videoFormat(int width, float jpegQuality) { videoWidth = width; quality = jpegQuality; return this; }

    /** The scripted takes known to {@code --scenario}. */
    public static Map<String, List<Take>> scenarios(Workflow w) {
        Map<String, List<Take>> m = new LinkedHashMap<>();
        Take live = new Take("Live resident", "A person in front of the camera. Passive check first; challenges only if confidence is low.",
                Workflow.RESIDENT, Subject.LIVE, 11, List.of(), Map.of("passive", "01-passive-check.png"));
        Take op = new Take("Operator login, challenge-response", "Operators always get a random challenge sequence. The person follows each prompt.",
                Workflow.OPERATOR, Subject.LIVE, 3, List.of(), Map.of("challenge", "02-challenge-prompt.png", "result", "03-live-result.png"));
        Take print = new Take("Printed photo attack", "A printed photo of the same face. Texture is suspicious and a photo cannot follow a prompt.",
                Workflow.OPERATOR, Subject.PRINT, 3, List.of(), Map.of("result", "04-print-attack-rejected.png"));
        Take video = new Take("Video replay attack", "A recording that blinks, smiles and turns on its own schedule, ignoring the prompts.",
                Workflow.OPERATOR, Subject.VIDEO_REPLAY, 3, List.of(), Map.of("result", "05-replay-rejected.png"));
        Take swap = new Take("Attack lab: injection mid-session", "A live person starts; at 4 s the feed is swapped for an injected virtual-camera stream.",
                Workflow.OPERATOR, Subject.LIVE, 3, List.of(new Switch(4000, Subject.INJECTED)), Map.of("result", "06-injection-mid-session.png"));
        m.put("demo-reel", List.of(live, op, print, video, swap));
        m.put("live", List.of(new Take("Live person", "", w, Subject.LIVE, 3, List.of(), Map.of("passive", "passive.png", "challenge", "challenge.png", "result", "result.png"))));
        for (Subject s : Subject.values()) {
            if (s == Subject.LIVE) continue;
            m.put(s.key(), List.of(new Take(s.label(), "", w, s, 3, List.of(), Map.of("result", "result.png"))));
            m.put("live-then-" + s.key(), List.of(new Take("Live person, then " + s.label(), "", w, Subject.LIVE, 3,
                    List.of(new Switch(4000, s)), Map.of("result", "result.png"))));
        }
        return m;
    }

    /** Runs the takes in order into one video file (null = no video). */
    public List<TakeResult> record(List<Take> takes, String videoName) throws IOException, InterruptedException {
        Files.createDirectories(out);
        AviMjpegWriter avi = videoName == null ? null : new AviMjpegWriter(out.resolve(videoName), videoWidth, videoWidth * LabView.H / LabView.W / 2 * 2, fps, quality);
        List<TakeResult> results = new ArrayList<>();
        try {
            for (int i = 0; i < takes.size(); i++) {
                Take t = takes.get(i);
                if (avi != null && titleMs > 0) {
                    byte[] card = avi.jpeg(titleCard(t, i + 1, takes.size()));
                    for (long ms = 0; ms < titleMs; ms += Math.round(1000 / fps)) avi.addJpeg(card);
                }
                results.add(runTake(t, avi, takes.size() > 1 ? (i + 1) + "/" + takes.size() + "  " + t.title() : t.title()));
            }
        } finally {
            if (avi != null) {
                avi.close();
                written.add(out.resolve(videoName));
                log.printf(Locale.ROOT, "wrote %s (%d frames, %.1f s, %d KB)%n", out.resolve(videoName), avi.frameCount(),
                        avi.frameCount() / fps, Files.size(out.resolve(videoName)) / 1024);
            }
        }
        return results;
    }

    private TakeResult runTake(Take t, AviMjpegWriter avi, String caption) throws IOException, InterruptedException {
        LabView view = new LabView(new LabView.Controller() {
            @Override public void start(Workflow w, Subject s) { }
            @Override public void retry() { }
            @Override public void stop() { }
            @Override public void switchTo(Subject s) { }
        }, true);
        view.canvas().setCaption(caption);
        injection.set(t.subject() == Subject.INJECTED);
        CountDownLatch done = new CountDownLatch(1);
        AtomicReference<Snapshot> lastSnap = new AtomicReference<>();
        AtomicReference<Exception> error = new AtomicReference<>();
        long frameMs = Math.round(1000 / fps);
        long[] nextVideoMs = {0};
        int[] switchIdx = {0};
        Map<String, Boolean> shotTaken = new LinkedHashMap<>();
        long[] challengeSeenAt = {-1};
        long[] endAt = {-1};
        DemoSession[] holder = new DemoSession[1];
        DemoSession session = new DemoSession(engine, t.workflow(), t.subject(), Pacing.unthrottled(), t.seed(), snap -> {
            try {
                lastSnap.set(snap);
                DemoSession ds = holder[0];
                while (ds != null && switchIdx[0] < t.switches().size() && snap.elapsedMs() >= t.switches().get(switchIdx[0]).atMs()) {
                    Subject next = t.switches().get(switchIdx[0]++).subject();
                    injection.set(next == Subject.INJECTED);
                    ds.switchTo(next);
                }
                SessionState st = snap.state();
                Phase p = st.phase();
                view.show(snap, true);
                BufferedImage frame = null;
                if (avi != null) {
                    while (snap.elapsedMs() >= nextVideoMs[0]) {
                        if (frame == null) frame = view.renderOffscreen();
                        avi.addFrame(frame);
                        nextVideoMs[0] += frameMs;
                    }
                }
                if (p == Phase.PASSIVE_EVALUATING && snap.framesAnalyzed() >= 18) shot(t, "passive", view, shotTaken, frame);
                if (p == Phase.ACTIVE_CHALLENGE && snap.challenge() != null) {
                    if (challengeSeenAt[0] < 0) challengeSeenAt[0] = snap.elapsedMs();
                    if (snap.elapsedMs() - challengeSeenAt[0] >= 1100) shot(t, "challenge", view, shotTaken, frame);
                }
                boolean ended = st.isTerminal() || p == Phase.FAILED || snap.elapsedMs() > MAX_SESSION_MS;
                if (ended && endAt[0] < 0) endAt[0] = snap.elapsedMs();
                if (ended) done.countDown();
            } catch (Exception e) {
                error.compareAndSet(null, e);
                done.countDown();
            }
        });
        holder[0] = session;
        log.printf(Locale.ROOT, "take '%s': workflow=%s subject=%s seed=%d%n", t.title(), t.workflow(), t.subject().label(), t.seed());
        session.start();
        boolean finished = done.await(15, TimeUnit.MINUTES);
        session.stop();
        Snapshot fin = session.snapshot();
        session.close();
        if (error.get() != null) throw new IOException("recording failed", error.get());
        if (!finished || fin == null) throw new IOException("take did not finish: " + t.title());
        view.show(fin, false);
        BufferedImage end = view.renderOffscreen();
        shot(t, "result", view, shotTaken, end);
        if (avi != null) {
            byte[] jpg = avi.jpeg(end);
            for (long ms = 0; ms < holdMs; ms += frameMs) avi.addJpeg(jpg);
        }
        String decision = LabCanvas.decision(fin);
        String hash = fin.result() == null ? null : fin.result().evidenceHash();
        log.printf(Locale.ROOT, "  -> %s %s after %.1f s; challenges=%s; evidence=%s%n", decision, fin.state(), fin.elapsedMs() / 1000.0,
                fin.result() == null ? "[]" : fin.result().challenges().stream().map(c -> c.challenge().type() + ":" + c.result()).toList(), hash);
        return new TakeResult(t, fin.state(), decision, fin.elapsedMs(), hash);
    }

    private void shot(Take t, String key, LabView view, Map<String, Boolean> taken, BufferedImage already) throws IOException {
        String name = t.shots().get(key);
        if (name == null || taken.containsKey(key)) return;
        taken.put(key, true);
        BufferedImage img = already != null ? already : view.renderOffscreen();
        Path p = out.resolve(name);
        writePng(img, p);
        written.add(p);
        log.printf(Locale.ROOT, "  screenshot %s (%d KB)%n", p, Files.size(p) / 1024);
    }

    /** Writes a PNG under {@value #PNG_LIMIT} bytes, reducing colour depth (then size) if the full image is larger. */
    static void writePng(BufferedImage img, Path p) throws IOException {
        byte[] b = png(img);
        int[] masks = {0xF8F8F8, 0xF0F0F0, 0xE0E0E0};
        for (int i = 0; b.length > PNG_LIMIT && i < masks.length; i++) b = png(posterize(img, masks[i]));
        if (b.length > PNG_LIMIT) {
            BufferedImage small = new BufferedImage(img.getWidth() * 3 / 4, img.getHeight() * 3 / 4, BufferedImage.TYPE_INT_RGB);
            Graphics2D g = small.createGraphics();
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            g.drawImage(posterize(img, 0xF0F0F0), 0, 0, small.getWidth(), small.getHeight(), null);
            g.dispose();
            b = png(small);
        }
        Files.write(p, b);
    }

    private static byte[] png(BufferedImage img) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        ImageIO.write(img, "png", bos);
        return bos.toByteArray();
    }

    private static BufferedImage posterize(BufferedImage src, int mask) {
        BufferedImage o = new BufferedImage(src.getWidth(), src.getHeight(), BufferedImage.TYPE_INT_RGB);
        int[] px = src.getRGB(0, 0, src.getWidth(), src.getHeight(), null, 0, src.getWidth());
        for (int i = 0; i < px.length; i++) px[i] &= mask;
        o.setRGB(0, 0, src.getWidth(), src.getHeight(), px, 0, src.getWidth());
        return o;
    }

    /** Full-frame title shown before each take in the video. */
    static BufferedImage titleCard(Take t, int n, int of) {
        BufferedImage img = new BufferedImage(LabView.W, LabView.H, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            g.setColor(new Color(0x17324F));
            g.fillRect(0, 0, img.getWidth(), img.getHeight());
            g.setColor(new Color(0x8FB3DE));
            g.setFont(new Font(Theme.SANS, Font.BOLD, 18));
            g.drawString("MOSIP VIVID  ·  ATTACK LAB" + (of > 1 ? "  ·  " + n + " / " + of : ""), 120, 250);
            g.setColor(Color.WHITE);
            g.setFont(new Font(Theme.SANS, Font.BOLD, 44));
            g.drawString(t.title(), 120, 320);
            g.setFont(new Font(Theme.SANS, Font.PLAIN, 22));
            g.setColor(new Color(0xD6E2F0));
            int y = 370;
            for (String line : wrap(g.getFontMetrics(), t.subtitle(), 1040)) { g.drawString(line, 120, y); y += 32; }
            g.setFont(new Font(Theme.SANS, Font.PLAIN, 16));
            g.setColor(new Color(0x8FB3DE));
            g.drawString("Workflow: " + t.workflow() + "    Subject: " + t.subject().label()
                    + (t.switches().isEmpty() ? "" : "    then " + t.switches().get(0).subject().label()), 120, y + 24);
            g.drawString("Real liveness-core orchestrator + real ONNX engine. Frames: device-mock synthetic renderer.", 120, 640);
        } finally {
            g.dispose();
        }
        return img;
    }

    private static List<String> wrap(FontMetrics fm, String s, int w) {
        List<String> out = new ArrayList<>();
        StringBuilder line = new StringBuilder();
        for (String word : s.split(" ")) {
            String t = line.length() == 0 ? word : line + " " + word;
            if (fm.stringWidth(t) > w && line.length() > 0) { out.add(line.toString()); line = new StringBuilder(word); }
            else line = new StringBuilder(t);
        }
        if (line.length() > 0) out.add(line.toString());
        return out;
    }
}
