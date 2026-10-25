package io.mosip.vivid.demo;

import io.mosip.vivid.api.Workflow;
import io.mosip.vivid.device.mock.Pacing;
import io.mosip.vivid.device.sbi.SbiEndpoint;
import io.mosip.vivid.device.sbi.SbiStreamConfig;
import io.mosip.vivid.device.sbi.SbiStreamFrameSource;
import io.mosip.vivid.engine.onnx.OnnxLivenessEngine;

import javax.swing.JFrame;
import javax.swing.JOptionPane;
import javax.swing.SwingUtilities;
import javax.swing.UIManager;
import java.awt.GraphicsEnvironment;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * MOSIP Vivid Attack Lab.
 *
 * <pre>
 * java -jar vivid-demo.jar [--models DIR] [--workflow resident|operator|supervisor] [--subject live|print|video|screen|injected]
 *                          [--sbi HOST:PORT] [--detector-threshold T]
 * java -Djava.awt.headless=true -jar vivid-demo.jar --record DIR --scenario NAME [--workflow W] [--fps N] [--video FILE]
 * </pre>
 */
public final class DemoApp {
    private DemoApp() {}

    static final String USAGE = """
            MOSIP Vivid Attack Lab
              (no flags)                 open the desktop window
              --models DIR               ONNX model directory (default: $MOSIP_VIVID_MODELS, ../liveness-engine-onnx/models,
                                         ./liveness-engine-onnx/models)
              --workflow W               resident | operator | supervisor (initial selection, or the workflow to record)
              --subject S                live | print | video | screen | injected (initial selection)
              --sbi HOST:PORT            use a real SBI face device's /stream instead of the attack-lab subjects
              --detector-threshold T     YuNet score threshold (default 0.35 for synthetic frames, 0.7 production for --sbi)
              --record DIR               headless: run --scenario and write PNG screenshots and an MJPEG AVI into DIR
              --scenario NAME            demo-reel | live | print | video | screen | injected | live-then-<subject>
              --video FILE               video file name inside DIR (default vivid-demo.avi for demo-reel, <scenario>.avi otherwise;
                                         'none' to skip)
              --fps N                    video frame rate (default 5)
              --video-width W            video width in pixels, height keeps 16:9 (default 1024; the UI is 1280x720)
              --hold-ms MS               how long each final result stays on screen in the video (default 4000)
              --title-ms MS              title card before each take (default 4000 for demo-reel, 0 otherwise)
              --quality Q                JPEG quality of video frames, 0..1 (default 0.55)
            """;

    public static void main(String[] args) throws Exception {
        Map<String, String> a;
        try {
            a = parse(args);
        } catch (IllegalArgumentException e) {
            System.err.println(e.getMessage());
            System.err.print(USAGE);
            System.exit(2);
            return;
        }
        if (a.containsKey("help")) { System.out.print(USAGE); return; }
        Workflow workflow = Workflow.valueOf(a.getOrDefault("workflow", a.containsKey("record") ? "operator" : "resident").toUpperCase(Locale.ROOT));
        boolean sbi = a.containsKey("sbi");
        Optional<Path> models = DemoSetup.findModels(a.get("models"));
        if (models.isEmpty()) {
            String msg = "ONNX models not found. Pass --models DIR or set MOSIP_VIVID_MODELS (see liveness-engine-onnx/models/README.md).";
            System.err.println(msg);
            if (!a.containsKey("record") && !GraphicsEnvironment.isHeadless()) JOptionPane.showMessageDialog(null, msg, "Attack Lab", JOptionPane.ERROR_MESSAGE);
            System.exit(1);
            return;
        }
        double threshold = a.containsKey("detector-threshold") ? Double.parseDouble(a.get("detector-threshold"))
                : sbi ? 0 : DemoSetup.SYNTHETIC_DETECTOR_THRESHOLD;
        OnnxLivenessEngine onnx = DemoSetup.engine(models.get(), threshold);
        AtomicBoolean injection = new AtomicBoolean();
        DemoEngine engine = new DemoEngine(onnx, injection::get);

        if (a.containsKey("record")) {
            String scenario = a.getOrDefault("scenario", "demo-reel");
            List<Recorder.Take> takes = Recorder.scenarios(workflow).get(scenario);
            if (takes == null) {
                System.err.println("unknown scenario '" + scenario + "'; known: " + Recorder.scenarios(workflow).keySet());
                System.exit(2);
            }
            String video = a.getOrDefault("video", scenario.equals("demo-reel") ? "vivid-demo.avi" : scenario + ".avi");
            double fps = Double.parseDouble(a.getOrDefault("fps", "5"));
            Recorder r = new Recorder(engine, injection, Path.of(a.get("record")), fps,
                    Long.parseLong(a.getOrDefault("hold-ms", "4000")),
                    Long.parseLong(a.getOrDefault("title-ms", scenario.equals("demo-reel") ? "4000" : "0")), System.out)
                    .videoFormat(Integer.parseInt(a.getOrDefault("video-width", "1024")), Float.parseFloat(a.getOrDefault("quality", "0.55")));
            List<Recorder.TakeResult> results = r.record(takes, "none".equals(video) ? null : video);
            System.out.println("summary:");
            for (Recorder.TakeResult t : results) System.out.printf(Locale.ROOT, "  %-45s %-9s %s%n", t.take().title(), t.decision(), t.finalState());
            engine.close();
            System.exit(0);
            return;
        }

        Subject subject = Subject.parse(a.getOrDefault("subject", "live"));
        SbiEndpoint endpoint = sbi ? endpoint(a.get("sbi")) : null;
        SwingUtilities.invokeLater(() -> openWindow(engine, injection, workflow, subject, endpoint));
    }

    private static SbiEndpoint endpoint(String hostPort) {
        int i = hostPort.lastIndexOf(':');
        if (i <= 0) throw new IllegalArgumentException("--sbi expects HOST:PORT");
        return new SbiEndpoint(hostPort.substring(0, i), Integer.parseInt(hostPort.substring(i + 1)));
    }

    private static void openWindow(DemoEngine engine, AtomicBoolean injection, Workflow workflow, Subject subject, SbiEndpoint sbi) {
        try { UIManager.setLookAndFeel(UIManager.getCrossPlatformLookAndFeelClassName()); } catch (Exception ignored) { }
        JFrame f = new JFrame("MOSIP Vivid · Attack Lab");
        Controller c = new Controller(engine, injection, sbi);
        LabView view = new LabView(c, sbi == null);
        c.view = view;
        view.show(Snapshot.idle(workflow, sbi == null ? subject.label() : "SBI device " + sbi, sbi == null,
                null), false);
        f.setContentPane(view);
        f.setDefaultCloseOperation(JFrame.DISPOSE_ON_CLOSE);
        f.addWindowListener(new java.awt.event.WindowAdapter() {
            @Override public void windowClosed(java.awt.event.WindowEvent e) { c.stop(); engine.close(); System.exit(0); }
        });
        f.pack();
        f.setLocationRelativeTo(null);
        f.setVisible(true);
    }

    /** Wires the controls to sessions. */
    private static final class Controller implements LabView.Controller {
        private final DemoEngine engine;
        private final AtomicBoolean injection;
        private final SbiEndpoint sbi;
        private LabView view;
        private DemoSession session;

        Controller(DemoEngine engine, AtomicBoolean injection, SbiEndpoint sbi) { this.engine = engine; this.injection = injection; this.sbi = sbi; }

        @Override public void start(Workflow w, Subject s) {
            stop();
            injection.set(sbi == null && s == Subject.INJECTED);
            java.util.function.Consumer<Snapshot> ui = snap -> SwingUtilities.invokeLater(() -> {
                if (session != null && snap.workflow() == session.workflow()) view.show(snap, true);
            });
            if (sbi == null) {
                session = new DemoSession(engine, w, s, Pacing.realTime(), null, ui);
            } else {
                SbiStreamFrameSource dev = new SbiStreamFrameSource(SbiStreamConfig.builder().endpoint(sbi).build());
                session = DemoSession.external(engine, w, dev, "SBI device " + sbi, ui);
            }
            view.show(Snapshot.idle(w, sbi == null ? s.label() : "SBI device " + sbi, sbi == null, null), true);
            DemoSession started = session;
            new Thread(started::start, "session-start").start();
        }

        @Override public void retry() {
            if (session != null) session.retry();
        }

        @Override public void stop() {
            DemoSession s = session;
            session = null;
            if (s != null) {
                Snapshot last = s.snapshot();
                new Thread(s::close, "session-close").start();
                if (last != null && view != null) view.show(last, false);
            }
        }

        @Override public void switchTo(Subject s) {
            if (session == null) return;
            injection.set(s == Subject.INJECTED);
            session.switchTo(s);
        }
    }

    static Map<String, String> parse(String[] args) {
        Map<String, String> m = new java.util.HashMap<>();
        for (int i = 0; i < args.length; i++) {
            String k = args[i];
            if (k.equals("-h") || k.equals("--help")) { m.put("help", ""); continue; }
            if (!k.startsWith("--")) throw new IllegalArgumentException("unexpected argument: " + k);
            String key = k.substring(2);
            if (key.contains("=")) { m.put(key.substring(0, key.indexOf('=')), key.substring(key.indexOf('=') + 1)); continue; }
            if (i + 1 >= args.length) throw new IllegalArgumentException("missing value for " + k);
            m.put(key, args[++i]);
        }
        return m;
    }
}
