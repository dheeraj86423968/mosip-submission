package io.mosip.vivid.device.sbi;

import java.io.Closeable;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Tolerant parser for {@code multipart/x-mixed-replace} (MJPEG) streams as produced by SBI {@code /stream}.
 * Robust to what real device services send:
 * <ul>
 *   <li>boundary taken from the Content-Type parameter, quoted or not, with or without a leading {@code --}
 *       (MockMDS advertises {@code boundary=--BoundaryString} and delimits with {@code --BoundaryString});</li>
 *   <li>CRLF or bare LF line endings; header lines with or without a space after the colon;</li>
 *   <li>missing, wrong or unparsable {@code Content-Length} (falls back to scanning for the next delimiter);</li>
 *   <li>zero-length parts (returned with an empty body so callers can skip them);</li>
 *   <li>garbage or preamble between parts (skipped and counted in {@link #bytesSkipped()});</li>
 *   <li>the closing {@code --boundary--} delimiter or a plain EOF.</li>
 * </ul>
 * Not thread-safe. Blocking reads propagate {@link java.net.SocketTimeoutException} from the underlying stream,
 * which is how stalls are detected upstream.
 */
public final class MultipartStreamReader implements Closeable {
    /** One multipart part: lower-cased header names and the raw body bytes. */
    public static final class Part {
        private final Map<String, String> headers;
        private final byte[] body;

        public Part(Map<String, String> headers, byte[] body) {
            this.headers = headers;
            this.body = body;
        }

        public Map<String, String> headers() { return headers; }
        public byte[] body() { return body; }

        public String contentType() { return headers.getOrDefault("content-type", ""); }
        public boolean isEmpty() { return body.length == 0; }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof Part)) return false;
            Part other = (Part) o;
            return java.util.Objects.equals(headers, other.headers)
                    && java.util.Objects.equals(body, other.body);
        }

        @Override
        public int hashCode() {
            int h = 0;
            h = 31 * h + java.util.Objects.hashCode(headers);
            h = 31 * h + java.util.Objects.hashCode(body);
            return h;
        }

        @Override
        public String toString() {
            return "Part[headers=" + headers + ", body=" + body + "]";
        }
    }

    private static final int MAX_PART_BYTES = 32 * 1024 * 1024;

    private static final int MAX_TRAILER_BYTES = 8 * 1024;

    private final ScanBuffer in;
    /** Delimiter in use; for a boundary that itself starts with "--" this is the short (MockMDS) form until the long form is seen. */
    private byte[] delimiter;
    /** The RFC 2046 form {@code "--" + boundary} while undecided (only when the boundary starts with "--"), else null. */
    private byte[] longDelimiter;
    private final java.io.ByteArrayOutputStream trailer = new java.io.ByteArrayOutputStream();
    private long partsRead;
    private long bytesSkipped;
    private boolean finished;

    public MultipartStreamReader(InputStream in, String contentType) {
        this(in, boundaryOf(contentType), 64 * 1024);
    }

    /** @param boundary the boundary token (leading dashes optional) */
    public MultipartStreamReader(InputStream in, String boundary, int bufferSize) {
        if (boundary == null || boundary.isEmpty()) throw new IllegalArgumentException("empty boundary");
        // RFC 2046: delimiter = "--" + boundary. MockMDS advertises boundary=--BoundaryString but writes
        // --BoundaryString, so for a boundary that already starts with "--" both forms are accepted until the
        // first delimiter line shows which one the service uses.
        if (boundary.startsWith("--")) {
            this.delimiter = boundary.getBytes(StandardCharsets.ISO_8859_1);
            this.longDelimiter = ("--" + boundary).getBytes(StandardCharsets.ISO_8859_1);
        } else {
            this.delimiter = ("--" + boundary).getBytes(StandardCharsets.ISO_8859_1);
        }
        this.in = new ScanBuffer(in, bufferSize);
    }

    /** Extract the boundary parameter from a Content-Type value; throws if absent. */
    public static String boundaryOf(String contentType) {
        if (contentType == null) throw new IllegalArgumentException("no Content-Type");
        for (String param : contentType.split(";")) {
            String p = param.trim();
            int eq = p.indexOf('=');
            if (eq > 0 && p.substring(0, eq).trim().equalsIgnoreCase("boundary")) {
                String v = p.substring(eq + 1).trim();
                if (v.length() >= 2 && v.startsWith("\"") && v.endsWith("\"")) v = v.substring(1, v.length() - 1);
                if (!v.isEmpty()) return v;
            }
        }
        throw new IllegalArgumentException("no boundary parameter in Content-Type '" + contentType + "'");
    }

    public long partsRead() { return partsRead; }
    /** Bytes discarded between parts (preamble, garbage, stray line breaks). */
    public long bytesSkipped() { return bytesSkipped; }

    /**
     * Bytes that followed the last part up to the end of the stream (at most 8 KiB), e.g. the HTTP response with an
     * SBI error JSON that MockMDS appends after its multipart body when the stream times out. Empty if none.
     */
    public byte[] trailer() { return trailer.toByteArray(); }

    /**
     * Read the next part, or {@code null} after the closing delimiter or at a clean end of stream.
     *
     * @throws EOFException if the stream ends in the middle of a part
     */
    public Part next() throws IOException {
        if (finished) return null;
        // 1. find a delimiter line (skipping anything before it)
        trailer.reset();
        while (true) {
            byte[] line = in.readLine();
            if (line == null) { finished = true; return null; }
            if (longDelimiter != null && startsWith(line, longDelimiter)) { delimiter = longDelimiter; longDelimiter = null; }
            if (startsWith(line, delimiter)) {
                if (longDelimiter != null) longDelimiter = null;   // the short form is in use
                if (line.length >= delimiter.length + 2 && line[delimiter.length] == '-' && line[delimiter.length + 1] == '-') { finished = true; return null; }
                break;
            }
            bytesSkipped += line.length + 1;
            if (trailer.size() < MAX_TRAILER_BYTES) { trailer.write(line, 0, Math.min(line.length, MAX_TRAILER_BYTES - trailer.size())); trailer.write('\n'); }
        }
        // 2. headers
        Map<String, String> headers = new LinkedHashMap<>();
        while (true) {
            byte[] line = in.readLine();
            if (line == null) throw new EOFException("stream ended inside part headers");
            if (line.length == 0) break;
            String s = new String(line, StandardCharsets.ISO_8859_1);
            int colon = s.indexOf(':');
            if (colon <= 0) continue;
            headers.put(s.substring(0, colon).trim().toLowerCase(Locale.ROOT), s.substring(colon + 1).trim());
        }
        // 3. body
        long declared = -1;
        String cl = headers.get("content-length");
        if (cl != null) { try { declared = Long.parseLong(cl.trim()); } catch (NumberFormatException ignored) { } }
        byte[] body;
        if (declared >= 0 && declared <= MAX_PART_BYTES) {
            body = in.readExact((int) declared);
            if (body == null) throw new EOFException("stream ended inside part body (" + declared + " bytes declared)");
            in.skipLineBreaks();
            if (in.hasMore() && !in.peekStartsWith(delimiter)) {
                // Content-Length did not match the framing: trust it, discard whatever precedes the next delimiter
                byte[] rest = in.readUntil(delimiter, MAX_PART_BYTES);
                if (rest == null) {
                    byte[] tail = in.drain();
                    bytesSkipped += tail.length;
                    trailer.write(tail, 0, Math.min(tail.length, MAX_TRAILER_BYTES));
                    finished = true;
                }
                else { bytesSkipped += rest.length; in.unreadDelimiterLine(delimiter); }
            }
        } else {
            byte[] raw = in.readUntil(delimiter, MAX_PART_BYTES);
            if (raw == null) throw new EOFException("stream ended inside unframed part body");
            body = stripTrailingBreak(raw);
            in.unreadDelimiterLine(delimiter);
        }
        partsRead++;
        return new Part(Collections.unmodifiableMap(headers), body);
    }

    @Override public void close() throws IOException { in.close(); }

    private static boolean startsWith(byte[] a, byte[] prefix) {
        if (a.length < prefix.length) return false;
        for (int i = 0; i < prefix.length; i++) if (a[i] != prefix[i]) return false;
        return true;
    }

    private static byte[] stripTrailingBreak(byte[] b) {
        int n = b.length;
        if (n > 0 && b[n - 1] == '\n') n--;
        if (n > 0 && b[n - 1] == '\r') n--;
        return n == b.length ? b : Arrays.copyOf(b, n);
    }

    /** Growable read buffer with line, exact-length and delimiter-search reads plus limited push-back. */
    static final class ScanBuffer implements Closeable {
        private final InputStream in;
        private byte[] buf;
        private int pos, limit;
        private boolean eof;

        ScanBuffer(InputStream in, int size) { this.in = in; this.buf = new byte[Math.max(1024, size)]; }

        private boolean fill() throws IOException {
            if (eof) return false;
            if (pos > 0 && pos == limit) { pos = limit = 0; }
            else if (pos > buf.length / 2) { System.arraycopy(buf, pos, buf, 0, limit - pos); limit -= pos; pos = 0; }
            if (limit == buf.length) buf = Arrays.copyOf(buf, buf.length * 2);
            int n = in.read(buf, limit, buf.length - limit);
            if (n < 0) { eof = true; return false; }
            limit += n;
            return true;
        }

        private int available() { return limit - pos; }

        /** A line without its CR/LF terminator; null at EOF with nothing pending. */
        byte[] readLine() throws IOException {
            int scanFrom = pos;
            while (true) {
                for (int i = scanFrom; i < limit; i++) {
                    if (buf[i] == '\n') {
                        int end = i;
                        if (end > pos && buf[end - 1] == '\r') end--;
                        byte[] line = Arrays.copyOfRange(buf, pos, end);
                        pos = i + 1;
                        return line;
                    }
                }
                scanFrom = limit;
                int before = pos;
                if (!fill()) {
                    if (available() == 0) return null;
                    byte[] line = Arrays.copyOfRange(buf, pos, limit);
                    pos = limit;
                    return stripTrailingBreak(line);
                }
                scanFrom -= (before - pos);   // compaction may have shifted data
            }
        }

        /** Exactly n bytes, or null at EOF. */
        byte[] readExact(int n) throws IOException {
            while (available() < n) if (!fill()) return null;
            byte[] out = Arrays.copyOfRange(buf, pos, pos + n);
            pos += n;
            return out;
        }

        /** Consume any CR/LF bytes at the current position. */
        void skipLineBreaks() throws IOException {
            while (true) {
                if (available() == 0 && !fill()) return;
                byte b = buf[pos];
                if (b == '\r' || b == '\n') pos++; else return;
            }
        }

        /** True if at least one more byte can be read. */
        boolean hasMore() throws IOException { return available() > 0 || fill(); }

        /** Bytes left unread at EOF (used for accounting when a stream ends mid-resync). */
        byte[] drain() { byte[] out = Arrays.copyOfRange(buf, pos, limit); pos = limit; return out; }

        boolean peekStartsWith(byte[] pattern) throws IOException {
            while (available() < pattern.length) if (!fill()) return false;
            for (int i = 0; i < pattern.length; i++) if (buf[pos + i] != pattern[i]) return false;
            return true;
        }

        /** Bytes before the next occurrence of pattern (pattern consumed); null at EOF. */
        byte[] readUntil(byte[] pattern, int max) throws IOException {
            int scanFrom = pos;
            while (true) {
                int idx = indexOf(pattern, scanFrom);
                if (idx >= 0) {
                    byte[] out = Arrays.copyOfRange(buf, pos, idx);
                    pos = idx + pattern.length;
                    return out;
                }
                if (available() > max) throw new IOException("part exceeds " + max + " bytes without a delimiter");
                int before = pos;
                scanFrom = Math.max(pos, limit - pattern.length + 1);
                if (!fill()) return null;
                scanFrom -= (before - pos);
            }
        }

        private int indexOf(byte[] pattern, int from) {
            outer:
            for (int i = Math.max(from, pos); i <= limit - pattern.length; i++) {
                for (int j = 0; j < pattern.length; j++) if (buf[i + j] != pattern[j]) continue outer;
                return i;
            }
            return -1;
        }

        /** Push the delimiter back so the next {@link #readLine()} sees it as a delimiter line. */
        void unreadDelimiterLine(byte[] delimiter) {
            if (pos >= delimiter.length) { pos -= delimiter.length; System.arraycopy(delimiter, 0, buf, pos, delimiter.length); return; }
            byte[] nb = new byte[Math.max(buf.length, available() + delimiter.length + 1024)];
            System.arraycopy(delimiter, 0, nb, 0, delimiter.length);
            System.arraycopy(buf, pos, nb, delimiter.length, available());
            limit = delimiter.length + available();
            pos = 0;
            buf = nb;
        }

        @Override public void close() throws IOException { in.close(); }
    }
}
