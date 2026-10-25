package io.mosip.vivid.telemetry.calibration;

import io.mosip.vivid.telemetry.json.Json;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Durable privacy-budget ledger of one client (design 14-dp-calibration.md section 5).
 *
 * <p>Every release is charged <em>before</em> any noisy count leaves the client: the entry is written to a temporary
 * file, forced to disk and atomically moved over the ledger, and only then does {@link #tryCharge} return true. A crash
 * between charging and sending therefore wastes budget but can never spend it twice. A release id can be charged once:
 * re-releasing the same data with fresh noise would let a recipient average the noise away.</p>
 *
 * <p>Accounting: pure-DP (Laplace) releases compose by summing epsilon; zCDP (Gaussian) releases compose by summing
 * rho. The guarantee reported and enforced is {@code epsilon_total = sum(epsilon_i) + rho + 2 sqrt(rho ln(1/delta))},
 * i.e. (epsilon_total, delta)-DP for the whole history. When the next release would exceed the budget the ledger
 * refuses it, permanently: the budget is a lifetime budget of the ledger file, and renewing it is a policy decision
 * that is not made in code.</p>
 *
 * <p>Thread-safe.</p>
 */
public final class PrivacyBudgetLedger {

    /** One charged release. */
    public static final class Entry {
        private final String releaseId;
        private final Instant at;
        private final String mechanism;
        private final double epsilon;
        private final double rho;

        public Entry(String releaseId, Instant at, String mechanism, double epsilon, double rho) {
            this.releaseId = releaseId;
            this.at = at;
            this.mechanism = mechanism;
            this.epsilon = epsilon;
            this.rho = rho;
        }

        public String releaseId() { return releaseId; }
        public Instant at() { return at; }
        public String mechanism() { return mechanism; }
        public double epsilon() { return epsilon; }
        public double rho() { return rho; }
    }

    private final Path file;
    private final double budgetEpsilon;
    private final double delta;
    private final List<Entry> entries = new ArrayList<>();
    private final Object lock = new Object();

    /**
     * @param file          ledger file, or {@code null} for an in-memory ledger (tests, simulation)
     * @param budgetEpsilon lifetime epsilon budget
     * @param delta         delta at which zCDP charges are converted
     */
    public PrivacyBudgetLedger(Path file, double budgetEpsilon, double delta) {
        if (!(budgetEpsilon > 0) || !(delta > 0 && delta < 1)) {
            throw new IllegalArgumentException("need budgetEpsilon > 0 and 0 < delta < 1");
        }
        this.file = file;
        this.budgetEpsilon = budgetEpsilon;
        this.delta = delta;
        if (file != null && Files.exists(file)) {
            load();
        }
    }

    /** epsilon of rho-zCDP at the given delta: rho + 2 sqrt(rho ln(1/delta)). */
    public static double zcdpToEpsilon(double rho, double delta) {
        return rho <= 0 ? 0 : rho + 2 * Math.sqrt(rho * Math.log(1 / delta));
    }

    /** Lifetime budget. */
    public double budgetEpsilon() {
        return budgetEpsilon;
    }

    public double delta() {
        return delta;
    }

    /** epsilon spent so far (at {@link #delta()}). */
    public double spentEpsilon() {
        synchronized (lock) {
            return total(0, 0);
        }
    }

    /** Budget left; never negative. */
    public double remainingEpsilon() {
        return Math.max(0, budgetEpsilon - spentEpsilon());
    }

    /** Snapshot of charged releases. */
    public List<Entry> entries() {
        synchronized (lock) {
            return Collections.unmodifiableList(new ArrayList<>(entries));
        }
    }

    /**
     * Charges a release if the budget allows it and persists the charge.
     *
     * @param pureEpsilon epsilon of a pure-DP release (0 if none)
     * @param rho         rho of a zCDP release (0 if none)
     * @return true if charged; false if the budget would be exceeded or the release id was already charged
     */
    public boolean tryCharge(String releaseId, String mechanism, double pureEpsilon, double rho, Instant at) {
        Objects.requireNonNull(releaseId, "releaseId");
        if (pureEpsilon < 0 || rho < 0 || (pureEpsilon == 0 && rho == 0)) {
            throw new IllegalArgumentException("a release must cost something");
        }
        synchronized (lock) {
            for (Entry e : entries) {
                if (e.releaseId.equals(releaseId)) {
                    return false;
                }
            }
            if (total(pureEpsilon, rho) > budgetEpsilon + 1e-12) {
                return false;
            }
            entries.add(new Entry(releaseId, at, mechanism, pureEpsilon, rho));
            try {
                persist();
            } catch (UncheckedIOException e) {
                entries.remove(entries.size() - 1);
                throw e;
            }
            return true;
        }
    }

    private double total(double extraEpsilon, double extraRho) {
        double eps = extraEpsilon;
        double rho = extraRho;
        for (Entry e : entries) {
            eps += e.epsilon;
            rho += e.rho;
        }
        return eps + zcdpToEpsilon(rho, delta);
    }

    private void persist() {
        if (file == null) {
            return;
        }
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("budgetEpsilon", budgetEpsilon);
        m.put("delta", delta);
        List<Object> list = new ArrayList<>();
        for (Entry e : entries) {
            Map<String, Object> x = new LinkedHashMap<>();
            x.put("releaseId", e.releaseId);
            x.put("at", e.at.toString());
            x.put("mechanism", e.mechanism);
            x.put("epsilon", e.epsilon);
            x.put("rho", e.rho);
            list.add(x);
        }
        m.put("entries", list);
        byte[] bytes = Json.write(m).getBytes(StandardCharsets.UTF_8);
        try {
            Path abs = file.toAbsolutePath();
            if (abs.getParent() != null) {
                Files.createDirectories(abs.getParent());
            }
            Path tmp = abs.resolveSibling(abs.getFileName() + ".tmp");
            try (FileChannel ch = FileChannel.open(tmp, StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING)) {
                ByteBuffer buf = ByteBuffer.wrap(bytes);
                while (buf.hasRemaining()) {
                    ch.write(buf);
                }
                ch.force(true);
            }
            Files.move(tmp, abs, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            throw new UncheckedIOException("cannot persist privacy ledger " + file.getFileName(), e);
        }
    }

    @SuppressWarnings("unchecked")
    private void load() {
        try {
            Map<String, Object> m = Json.parseObject(new String(Files.readAllBytes(file), StandardCharsets.UTF_8));
            for (Object o : (List<Object>) m.get("entries")) {
                Map<String, Object> x = (Map<String, Object>) o;
                entries.add(new Entry((String) x.get("releaseId"), Instant.parse((String) x.get("at")), (String) x.get("mechanism"),
                        ((Number) x.get("epsilon")).doubleValue(), ((Number) x.get("rho")).doubleValue()));
            }
        } catch (IOException e) {
            throw new UncheckedIOException("cannot read privacy ledger " + file.getFileName(), e);
        }
    }
}
