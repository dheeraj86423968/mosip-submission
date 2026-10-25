package io.mosip.vivid.device.sbi;

import java.util.Objects;

/** Host and port of one SBI service instance. */
public final class SbiEndpoint {
    private final String host;
    private final int port;

    public SbiEndpoint(String host, int port) {
        this.host = Objects.requireNonNull(host, "host");
        if (port <= 0 || port > 65535) throw new IllegalArgumentException("bad port " + port);
        this.port = port;
    }

    /** {@code 127.0.0.1:port}. */
    public static SbiEndpoint local(int port) { return new SbiEndpoint(SbiVerbs.HOST, port); }

    public String host() { return host; }
    public int port() { return port; }
    public String baseUrl() { return "http://" + host + ":" + port; }

    @Override public boolean equals(Object o) { if (!(o instanceof SbiEndpoint)) return false; SbiEndpoint e = (SbiEndpoint) o; return e.port == port && e.host.equals(host); }
    @Override public int hashCode() { return Objects.hash(host, port); }
    @Override public String toString() { return host + ":" + port; }
}
