package io.mosip.vivid.device.sbi;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.ConnectException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/**
 * Scans the SBI port range (default 4501..4600 on 127.0.0.1) in parallel with short timeouts and returns every
 * face device entry found. Ports with nothing listening are skipped silently; ports that answer with garbage
 * are logged and skipped. A scan of 100 ports with a 500 ms timeout typically finishes well under a second.
 */
public final class SbiDiscovery {
    private static final Logger LOG = LoggerFactory.getLogger(SbiDiscovery.class);

    private final SbiClient client;
    private final String host;
    private final int portFrom;
    private final int portTo;
    private final int parallelism;

    public SbiDiscovery(SbiClient client, String host, int portFrom, int portTo, int parallelism) {
        if (portFrom > portTo) throw new IllegalArgumentException("portFrom > portTo");
        this.client = client; this.host = host; this.portFrom = portFrom; this.portTo = portTo;
        this.parallelism = Math.max(1, parallelism);
    }

    /** Scan 127.0.0.1:4501-4600 with the given client. */
    public static SbiDiscovery standard(SbiClient client) {
        return new SbiDiscovery(client, SbiVerbs.HOST, SbiVerbs.PORT_MIN, SbiVerbs.PORT_MAX, 16);
    }

    /** All face devices across the range, ordered by port then deviceId. */
    public List<SbiDeviceInfo> discoverFaceDevices() {
        ExecutorService pool = Executors.newFixedThreadPool(parallelism, r -> { Thread t = new Thread(r, "sbi-discovery"); t.setDaemon(true); return t; });
        List<Future<List<SbiDeviceInfo>>> futures = new ArrayList<>();
        try {
            for (int port = portFrom; port <= portTo; port++) {
                SbiEndpoint ep = new SbiEndpoint(host, port);
                futures.add(pool.submit(() -> probe(ep)));
            }
            List<SbiDeviceInfo> out = new ArrayList<>();
            for (Future<List<SbiDeviceInfo>> f : futures) {
                try { out.addAll(f.get()); }
                catch (InterruptedException e) { Thread.currentThread().interrupt(); break; }
                catch (java.util.concurrent.ExecutionException e) { LOG.debug("probe failed", e.getCause()); }
            }
            out.sort(Comparator.comparingInt((SbiDeviceInfo d) -> d.endpoint().port()).thenComparing(SbiDeviceInfo::deviceId));
            return out;
        } finally {
            pool.shutdownNow();
            try { pool.awaitTermination(2, TimeUnit.SECONDS); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        }
    }

    /** Probe a single endpoint; empty if nothing usable answers. */
    public List<SbiDeviceInfo> probe(SbiEndpoint ep) {
        try {
            List<SbiDeviceInfo> found = new ArrayList<>();
            for (SbiDeviceInfo d : client.discover(ep, "Face")) {
                if (!d.isFace()) continue;
                if (!d.errorCode().isEmpty() && !"0".equals(d.errorCode()) && d.deviceId().isEmpty()) {
                    LOG.debug("{} answered discovery with error {} {}", ep, d.errorCode(), d.errorInfo());
                    continue;
                }
                found.add(d);
            }
            if (!found.isEmpty()) LOG.info("SBI face device(s) at {}: {}", ep, found);
            return found;
        } catch (ConnectException | java.net.SocketTimeoutException e) {
            return List.of();                       // nothing listening, or not answering: normal for most ports
        } catch (IOException | RuntimeException e) {
            LOG.debug("{} is not an SBI face service: {}", ep, e.toString());
            return List.of();
        }
    }

    /**
     * Pick a device: by id if given, else the first Ready registration device, else the first Ready device,
     * else the first device at all.
     */
    public static Optional<SbiDeviceInfo> select(List<SbiDeviceInfo> devices, String deviceId, boolean preferRegistration) {
        if (deviceId != null && !deviceId.isBlank()) {
            return devices.stream().filter(d -> deviceId.equals(d.deviceId()) || deviceId.equals(d.deviceCode())).findFirst();
        }
        Optional<SbiDeviceInfo> best = devices.stream().filter(d -> d.isReady() && (!preferRegistration || d.isRegistration())).findFirst();
        if (best.isPresent()) return best;
        best = devices.stream().filter(SbiDeviceInfo::isReady).findFirst();
        return best.isPresent() ? best : devices.stream().findFirst();
    }
}
