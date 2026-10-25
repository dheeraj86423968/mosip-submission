package io.mosip.vivid.device.sbi;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Minimal HTTP/1.x client over a plain socket. SBI uses non-standard verbs ({@code MOSIPDISC}, {@code STREAM}, ...),
 * some services answer with {@code HTTP/1.0} and no framing (MockMDS), and stream consumers need a per-read
 * timeout to detect stalls; a raw socket gives all three without fighting a general-purpose client.
 * One request per connection ({@code Connection: close}).
 */
final class SbiHttp {
    private SbiHttp() {}

    /** Reads to EOF; stands in for {@code InputStream.readAllBytes()}, which Android has only from API 33. */
    static byte[] readFully(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) != -1) out.write(buf, 0, n);
        return out.toByteArray();
    }

    /** A response whose body may still be streaming. Closing it closes the socket. */
    static final class Response implements Closeable {
        private final Socket socket;
        final int status;
        final String reason;
        final Map<String, String> headers;   // lower-case names
        final InputStream body;

        Response(Socket socket, int status, String reason, Map<String, String> headers, InputStream body) {
            this.socket = socket; this.status = status; this.reason = reason; this.headers = headers; this.body = body;
        }

        String header(String name) { return headers.get(name.toLowerCase(Locale.ROOT)); }
        String contentType() { String ct = header("content-type"); return ct == null ? "" : ct; }
        /** Per-read timeout on the socket (0 = infinite). */
        void setReadTimeout(int millis) throws IOException { socket.setSoTimeout(millis); }
        byte[] readAll() throws IOException { try (InputStream in = body) { return readFully(in); } }
        String readAllText() throws IOException { return new String(readAll(), StandardCharsets.UTF_8); }
        @Override public void close() { try { socket.close(); } catch (IOException ignored) { } }
    }

    /**
     * Send one request and parse status + headers; the body is left as a stream.
     *
     * @param readTimeoutMs socket read timeout used both for the headers and (until changed) the body
     */
    static Response request(SbiEndpoint ep, String method, String path, byte[] body, String contentType, int connectTimeoutMs, int readTimeoutMs) throws IOException {
        Socket socket = new Socket();
        try {
            socket.setTcpNoDelay(true);
            socket.connect(new InetSocketAddress(ep.host(), ep.port()), connectTimeoutMs);
            socket.setSoTimeout(readTimeoutMs);
            StringBuilder req = new StringBuilder();
            req.append(method).append(' ').append(path).append(" HTTP/1.1\r\n");
            req.append("Host: ").append(ep.host()).append(':').append(ep.port()).append("\r\n");
            req.append("User-Agent: mosip-vivid-device-sbi-stream\r\n");
            req.append("Accept: */*\r\n");
            req.append("Connection: close\r\n");
            if (body != null) {
                req.append("Content-Type: ").append(contentType == null ? "application/json" : contentType).append("\r\n");
                req.append("Content-Length: ").append(body.length).append("\r\n");
            } else {
                req.append("Content-Length: 0\r\n");
            }
            req.append("\r\n");
            // Headers and body in one write: MockMDS reads the body with a single Reader.read(char[], 0, len)
            // after the header lines, so a body arriving in a later TCP segment can be cut short.
            byte[] head = req.toString().getBytes(StandardCharsets.US_ASCII);
            byte[] wire = body == null ? head : java.util.Arrays.copyOf(head, head.length + body.length);
            if (body != null) System.arraycopy(body, 0, wire, head.length, body.length);
            OutputStream out = socket.getOutputStream();
            out.write(wire);
            out.flush();

            InputStream in = new BufferedInputStream(socket.getInputStream(), 64 * 1024);
            String statusLine = readLine(in);
            if (statusLine == null) throw new EOFException("no HTTP status line from " + ep);
            String[] parts = statusLine.split(" ", 3);
            if (parts.length < 2 || !parts[0].startsWith("HTTP/")) throw new IOException("malformed status line '" + statusLine + "' from " + ep);
            int status;
            try { status = Integer.parseInt(parts[1].trim()); } catch (NumberFormatException e) { throw new IOException("malformed status '" + statusLine + "'"); }
            String reason = parts.length == 3 ? parts[2] : "";
            Map<String, String> headers = new LinkedHashMap<>();
            String line;
            while ((line = readLine(in)) != null && !line.isEmpty()) {
                int colon = line.indexOf(':');
                if (colon <= 0) continue;
                headers.put(line.substring(0, colon).trim().toLowerCase(Locale.ROOT), line.substring(colon + 1).trim());
            }
            InputStream bodyStream;
            String te = headers.get("transfer-encoding");
            String cl = headers.get("content-length");
            if (te != null && te.toLowerCase(Locale.ROOT).contains("chunked")) bodyStream = new ChunkedInputStream(in);
            else if (cl != null) {
                long n;
                try { n = Long.parseLong(cl.trim()); } catch (NumberFormatException e) { n = -1; }
                bodyStream = n >= 0 ? new BoundedInputStream(in, n) : in;
            } else bodyStream = in;   // HTTP/1.0 style: body until close
            return new Response(socket, status, reason, Collections.unmodifiableMap(headers), bodyStream);
        } catch (IOException | RuntimeException e) {
            try { socket.close(); } catch (IOException ignored) { }
            throw e;
        }
    }

    private static String readLine(InputStream in) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream(128);
        int c;
        while ((c = in.read()) >= 0) {
            if (c == '\n') break;
            bos.write(c);
        }
        if (c < 0 && bos.size() == 0) return null;
        byte[] b = bos.toByteArray();
        int len = b.length;
        if (len > 0 && b[len - 1] == '\r') len--;
        return new String(b, 0, len, StandardCharsets.ISO_8859_1);
    }

    /** Reads at most {@code limit} bytes from the underlying stream. */
    static final class BoundedInputStream extends InputStream {
        private final InputStream in; private long left;
        BoundedInputStream(InputStream in, long limit) { this.in = in; this.left = limit; }
        @Override public int read() throws IOException { if (left <= 0) return -1; int c = in.read(); if (c >= 0) left--; return c; }
        @Override public int read(byte[] b, int off, int len) throws IOException {
            if (left <= 0) return -1;
            int n = in.read(b, off, (int) Math.min(len, left));
            if (n > 0) left -= n;
            return n;
        }
        @Override public void close() throws IOException { in.close(); }
    }
}
