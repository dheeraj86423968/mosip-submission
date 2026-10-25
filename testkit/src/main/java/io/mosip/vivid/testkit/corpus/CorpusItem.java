package io.mosip.vivid.testkit.corpus;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * One corpus item on disk: a directory named by the item id ({@code docs/corpus-ethics.md} §10.1, e.g.
 * {@code P07-VIDEO_REPLAY_PHONE-L2-D1-T1}) holding numbered frames ({@code 00001.jpg}, {@code 00002.jpg}, ... as
 * written by {@code ffmpeg -vf fps=15 .../%05d.jpg}; {@code .jpg}, {@code .jpeg} and {@code .png} accepted, sorted
 * by file name) and optionally {@code timestamps.txt}. Each non-comment line of {@code timestamps.txt} is either
 * {@code <millis>} (one line per frame, in frame order) or {@code <file name> <millis>}; without the file the frames
 * are spaced at the {@code --fps} rate. Timestamps are made relative to the first frame.
 */
public record CorpusItem(String id, Path dir, List<Path> frames, long[] timestampsMillis) {
    /** {@code <participant>-<label>-<lighting>-<device>-T<take>}; the label itself uses underscores, never '-'. */
    public static final Pattern ITEM_NAME = Pattern.compile("^(P\\d+)-([A-Z0-9_]+)-(L\\d+)-(D\\d+)-T(\\d+)$");
    public static final String TIMESTAMPS_FILE = "timestamps.txt";

    /** Fields parsed from a §10.1 item name; null when the name does not follow the convention. */
    public record Name(String participant, String label, String lighting, String device, int take) {}

    public static Name parseName(String id) {
        Matcher m = ITEM_NAME.matcher(id);
        return m.matches() ? new Name(m.group(1), m.group(2), m.group(3), m.group(4), Integer.parseInt(m.group(5))) : null;
    }

    static boolean isFrame(Path p) {
        String n = p.getFileName().toString().toLowerCase(Locale.ROOT);
        return Files.isRegularFile(p) && (n.endsWith(".jpg") || n.endsWith(".jpeg") || n.endsWith(".png"));
    }

    /** Every directory under {@code root} (any depth, including {@code frames/} or {@code subjects/<id>/}) that holds frames. */
    public static List<CorpusItem> discover(Path root, double fps) throws IOException {
        List<CorpusItem> out = new ArrayList<>();
        try (Stream<Path> dirs = Files.walk(root)) {
            for (Path d : (Iterable<Path>) dirs.filter(Files::isDirectory).sorted()::iterator) {
                CorpusItem item = load(d, fps);
                if (item != null) out.add(item);
            }
        }
        return out;
    }

    /** The item in {@code dir}, or null when it holds no frames. */
    public static CorpusItem load(Path dir, double fps) throws IOException {
        List<Path> frames;
        try (Stream<Path> s = Files.list(dir)) { frames = s.filter(CorpusItem::isFrame).sorted().toList(); }
        if (frames.isEmpty()) return null;
        long[] ts = new long[frames.size()];
        Path tsFile = dir.resolve(TIMESTAMPS_FILE);
        if (Files.isRegularFile(tsFile)) {
            Map<String, Long> byName = new HashMap<>();
            List<Long> ordered = new ArrayList<>();
            int n = 0;
            for (String line : Files.readAllLines(tsFile, StandardCharsets.UTF_8)) {
                n++;
                String t = line.trim();
                if (t.isEmpty() || t.startsWith("#")) continue;
                String[] parts = t.split("[\\s,;]+");
                try {
                    if (parts.length == 1) ordered.add(Math.round(Double.parseDouble(parts[0])));
                    else byName.put(parts[0], Math.round(Double.parseDouble(parts[1])));
                } catch (NumberFormatException e) {
                    throw new IllegalArgumentException(tsFile + ":" + n + ": expected '<millis>' or '<file> <millis>'");
                }
            }
            for (int i = 0; i < frames.size(); i++) {
                Long v = byName.isEmpty() ? (i < ordered.size() ? ordered.get(i) : null) : byName.get(frames.get(i).getFileName().toString());
                if (v == null) throw new IllegalArgumentException(tsFile + ": no timestamp for " + frames.get(i).getFileName());
                ts[i] = v;
            }
        } else {
            if (!(fps > 0)) throw new IllegalArgumentException("fps must be positive");
            for (int i = 0; i < ts.length; i++) ts[i] = Math.round(i * 1000.0 / fps);
        }
        long t0 = ts[0];
        for (int i = 0; i < ts.length; i++) {
            ts[i] -= t0;
            if (i > 0 && ts[i] < ts[i - 1]) throw new IllegalArgumentException(dir + ": timestamps decrease at " + frames.get(i).getFileName());
        }
        return new CorpusItem(dir.getFileName().toString(), dir, frames, ts);
    }
}
