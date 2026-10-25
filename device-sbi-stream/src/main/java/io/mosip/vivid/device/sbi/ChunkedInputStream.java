package io.mosip.vivid.device.sbi;

import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;

/** Decodes HTTP/1.1 {@code Transfer-Encoding: chunked}. Chunk extensions and trailers are ignored. */
final class ChunkedInputStream extends InputStream {
    private final InputStream in;
    private long remaining;      // bytes left in the current chunk
    private boolean eof;
    private boolean first = true;

    ChunkedInputStream(InputStream in) { this.in = in; }

    @Override public int read() throws IOException {
        byte[] b = new byte[1];
        int n = read(b, 0, 1);
        return n < 0 ? -1 : b[0] & 0xff;
    }

    @Override public int read(byte[] b, int off, int len) throws IOException {
        if (len == 0) return 0;
        if (eof) return -1;
        if (remaining == 0) {
            if (!first) readLine();          // CRLF after previous chunk data
            first = false;
            String line = readLine();
            if (line == null) throw new EOFException("stream ended inside chunked encoding");
            int semi = line.indexOf(';');
            String hex = (semi >= 0 ? line.substring(0, semi) : line).trim();
            if (hex.isEmpty()) throw new IOException("bad chunk size line");
            remaining = Long.parseLong(hex, 16);
            if (remaining == 0) {
                String t;
                while ((t = readLine()) != null && !t.isEmpty()) { /* skip trailers */ }
                eof = true;
                return -1;
            }
        }
        int n = in.read(b, off, (int) Math.min(len, remaining));
        if (n < 0) throw new EOFException("stream ended inside chunk");
        remaining -= n;
        return n;
    }

    private String readLine() throws IOException {
        StringBuilder sb = new StringBuilder();
        int c;
        while ((c = in.read()) >= 0) {
            if (c == '\n') break;
            if (c != '\r') sb.append((char) c);
        }
        if (c < 0 && sb.length() == 0) return null;
        return sb.toString();
    }

    @Override public void close() throws IOException { in.close(); }
}
