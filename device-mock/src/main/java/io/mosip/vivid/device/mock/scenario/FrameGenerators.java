package io.mosip.vivid.device.mock.scenario;

import io.mosip.vivid.device.mock.render.FaceState;
import io.mosip.vivid.device.mock.render.Overlays;
import io.mosip.vivid.device.mock.render.SyntheticFaceRenderer;

import javax.imageio.ImageIO;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Random;
import java.util.stream.Stream;

/** Factory for the built-in {@link FrameGenerator}s used by {@link ScenarioTimeline}. */
public final class FrameGenerators {
    private FrameGenerators() {}

    /** Build the generator for a segment of a scenario. */
    public static FrameGenerator forSegment(Scenario scenario, Segment segment) {
        int w = scenario.width(), h = scenario.height();
        switch (segment.source()) {
            case Segment.SOURCE_SYNTHETIC_FACE: return new SyntheticFace(w, h, segment);
            case Segment.SOURCE_BLANK: return new Blank(w, h);
            case Segment.SOURCE_NOISE: return new Noise(w, h, segment.seed());
            default:
                Path dir = Path.of(segment.source());
                if (!dir.isAbsolute() && scenario.baseDir() != null) dir = scenario.baseDir().resolve(dir);
                return new Directory(dir, w, h);
        }
    }

    /** Cartoon face(s) driven by the segment's timeline, with optional attack overlay. */
    static final class SyntheticFace implements FrameGenerator {
        private final SyntheticFaceRenderer renderer;
        private final Segment segment;

        SyntheticFace(int w, int h, Segment segment) { this.renderer = new SyntheticFaceRenderer(w, h); this.segment = segment; }

        @Override public BufferedImage render(long t, long frameIndex) {
            long tl = segment.timeline().durationMs();
            long tt = (segment.timelineLoops() && tl > 0) ? t % tl : t;
            FaceState state = segment.timeline().stateAt(tt);
            BufferedImage img = renderer.newCanvas();
            int n = segment.faceCount();
            if (n == 1) {
                renderer.drawFace(img, state, 0.5, 0.5, 1.0);
            } else if (n > 1) {
                // spread faces across the frame; the second subject is slightly smaller and mirrors the first
                for (int i = 0; i < n; i++) {
                    double cx = (i + 1.0) / (n + 1.0);
                    double scale = i == 0 ? 0.75 : 0.65;
                    FaceState s = i == 0 ? state : new FaceState(-state.yawDeg(), state.pitchDeg(), state.eyeOpen(), 0, state.smile() * 0.5, 0);
                    renderer.drawFace(img, s, cx, 0.5, scale);
                }
            }
            Overlays.apply(segment.overlay(), img, segment.seed(), t);
            return img;
        }
    }

    /** Uniform mid-grey frame (lens cap, wall). */
    static final class Blank implements FrameGenerator {
        private final int w, h;
        Blank(int w, int h) { this.w = w; this.h = h; }
        @Override public BufferedImage render(long t, long frameIndex) {
            BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
            Graphics2D g = img.createGraphics();
            try { g.setColor(new java.awt.Color(0x60, 0x60, 0x60)); g.fillRect(0, 0, w, h); } finally { g.dispose(); }
            return img;
        }
    }

    /** Random RGB noise, re-seeded per frame so consecutive frames differ. */
    static final class Noise implements FrameGenerator {
        private final int w, h; private final long seed;
        Noise(int w, int h, long seed) { this.w = w; this.h = h; this.seed = seed; }
        @Override public BufferedImage render(long t, long frameIndex) {
            BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
            Random rnd = new Random(seed * 31 + frameIndex);
            int[] row = new int[w];
            for (int y = 0; y < h; y++) {
                for (int x = 0; x < w; x++) row[x] = rnd.nextInt() & 0xffffff;
                img.setRGB(0, y, w, 1, row, 0, w);
            }
            return img;
        }
    }

    /** Replays JPEG/PNG files from a directory in filename order, scaled to the scenario size, cycling at the end. */
    static final class Directory implements FrameGenerator {
        private final List<Path> files;
        private final int w, h;
        private final java.util.Map<Integer, BufferedImage> cache = new java.util.concurrent.ConcurrentHashMap<>();

        Directory(Path dir, int w, int h) {
            if (!Files.isDirectory(dir)) throw new IllegalArgumentException("frame directory not found: " + dir);
            try (Stream<Path> s = Files.list(dir)) {
                files = new ArrayList<>(s.filter(p -> {
                    String n = p.getFileName().toString().toLowerCase(Locale.ROOT);
                    return n.endsWith(".jpg") || n.endsWith(".jpeg") || n.endsWith(".png");
                }).sorted().toList());
            } catch (IOException e) { throw new UncheckedIOException(e); }
            if (files.isEmpty()) throw new IllegalArgumentException("no JPEG/PNG frames in " + dir);
            this.w = w; this.h = h;
        }

        @Override public BufferedImage render(long t, long frameIndex) {
            int idx = (int) (frameIndex % files.size());
            BufferedImage src = cache.computeIfAbsent(idx, i -> {
                try {
                    BufferedImage raw = ImageIO.read(files.get(i).toFile());
                    if (raw == null) throw new IllegalArgumentException("unreadable image " + files.get(i));
                    return raw;
                } catch (IOException e) { throw new UncheckedIOException(e); }
            });
            BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
            Graphics2D g = img.createGraphics();
            try { g.drawImage(src, 0, 0, w, h, null); } finally { g.dispose(); }
            return img;
        }
    }
}
