package io.mosip.vivid.testkit.redteam;

import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.BufferedWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * Renders the red-team corpus in the {@link io.mosip.vivid.testkit.corpus.CorpusScoreMain} layout.
 * <pre>
 * redteam-corpus --out &lt;dir&gt; [--subjects 10] [--takes 2] [--seed redteam-2026] [--species SIM_PRINT,...] [--threads 2]
 * </pre>
 * For each synthetic subject {@code P01..Pnn}: {@code --takes} bona fide sessions ({@code T1..Tk}) and one variant of
 * every attack species, all made from take 1's live session. Writes {@code <out>/<item>/00001.jpg ... timestamps.txt},
 * {@code <out>/capture-log.csv} (labels and attack classes) and {@code <out>/variants.jsonl} (one line per item:
 * variant id, item, species, every render parameter and the frames' SHA-256). No real media is read or written.
 * Exit codes: 0 ok, 2 usage error.
 */
public final class RedTeamCorpusMain {
    private RedTeamCorpusMain() {}

    public static void main(String[] args) { System.exit(run(args)); }

    public static int run(String[] args) {
        Path out = null;
        int subjects = 10, takes = 2, threads = 2;
        String seed = "redteam-2026";
        Set<AttackSpecies> species = EnumSet.allOf(AttackSpecies.class);
        try {
            for (int i = 0; i < args.length; i++) {
                switch (args[i]) {
                    case "--out" -> out = Path.of(args[++i]);
                    case "--subjects" -> subjects = Integer.parseInt(args[++i]);
                    case "--takes" -> takes = Integer.parseInt(args[++i]);
                    case "--threads" -> threads = Integer.parseInt(args[++i]);
                    case "--seed" -> seed = args[++i];
                    case "--species" -> {
                        species = EnumSet.of(AttackSpecies.BONA_FIDE);
                        for (String s : args[++i].split(",")) if (!s.isBlank()) species.add(AttackSpecies.parse(s));
                    }
                    case "--help", "-h" -> { usage(); return 0; }
                    default -> throw new IllegalArgumentException("unknown argument '" + args[i] + "'");
                }
            }
            if (out == null) throw new IllegalArgumentException("--out is required");
            if (subjects < 1 || subjects > 99 || takes < 1 || threads < 1) throw new IllegalArgumentException("--subjects 1..99, --takes and --threads >= 1");
            List<RedTeamVariant> variants = plan(subjects, takes, seed, species);
            Files.createDirectories(out);
            long t0 = System.nanoTime();
            ExecutorService pool = Executors.newFixedThreadPool(threads);
            List<Future<String>> hashes = new ArrayList<>();
            final Path dir = out;
            for (RedTeamVariant v : variants) hashes.add(pool.submit(() -> RedTeamRenderer.write(v, dir.resolve(v.itemId()))));
            pool.shutdown();
            try (BufferedWriter vw = Files.newBufferedWriter(out.resolve("variants.jsonl"), StandardCharsets.UTF_8);
                 BufferedWriter log = Files.newBufferedWriter(out.resolve("capture-log.csv"), StandardCharsets.UTF_8)) {
                log.write("# red-team corpus: synthetic drawn faces (device-mock) with simulated attacks; generator " + RedTeamVariant.GENERATOR + ", seed " + seed + "\n");
                log.write("item_id,participant_id,label,attack_class,lighting,device,take,consent_demographics,notes\n");
                for (int k = 0; k < variants.size(); k++) {
                    RedTeamVariant v = variants.get(k);
                    String sha = hashes.get(k).get();
                    ObjectNode n = v.toJson();
                    n.put("seed", seed);
                    n.put("framesSha256", sha);
                    vw.write(RedTeamVariant.JSON.writeValueAsString(n));
                    vw.write('\n');
                    log.write(String.join(",", v.itemId(), v.participant(), v.species().label(), v.species().paiClass(), "L0", "D0",
                            String.valueOf(v.take()), "N", "synthetic " + v.id()) + "\n");
                    System.out.printf(Locale.ROOT, "%-32s %s%n", v.itemId(), v.id());
                }
            }
            System.out.printf(Locale.ROOT, "rendered %d items (%d subjects) in %.1f s -> %s%n", variants.size(), subjects, (System.nanoTime() - t0) / 1e9, out);
            return 0;
        } catch (IllegalArgumentException | ArrayIndexOutOfBoundsException e) {
            System.err.println("redteam-corpus: " + (e instanceof ArrayIndexOutOfBoundsException ? "missing value for last option" : e.getMessage()));
            usage();
            return 2;
        } catch (Exception e) {
            System.err.println("redteam-corpus: " + e);
            e.printStackTrace();
            return 2;
        }
    }

    /** The variants of a run, in item order. */
    public static List<RedTeamVariant> plan(int subjects, int takes, String seed, Set<AttackSpecies> species) {
        List<RedTeamVariant> out = new ArrayList<>();
        for (int s = 1; s <= subjects; s++) {
            if (species.contains(AttackSpecies.BONA_FIDE)) for (int t = 1; t <= takes; t++) out.add(RedTeamVariant.draw(AttackSpecies.BONA_FIDE, s, t, seed));
            for (AttackSpecies sp : species) if (sp.isAttack()) out.add(RedTeamVariant.draw(sp, s, 1, seed));
        }
        return out;
    }

    private static void usage() {
        System.err.println("usage: redteam-corpus --out <dir> [--subjects 10] [--takes 2] [--seed redteam-2026] [--species SIM_PRINT,...] [--threads 2]");
    }
}
