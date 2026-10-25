package io.mosip.vivid.device.sbi;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Function;

/**
 * Replays raw HTTP responses recorded from MOSIP's MockMDS v1.2.0.2 ({@code src/test/resources/mockmds/*.http}).
 * One request per connection, like MockMDS: read the request line, headers and Content-Length body, write the
 * chosen recorded bytes verbatim, close.
 */
final class RecordedSbiServer implements AutoCloseable {
    /** A parsed request. */
    static final class Request {
        final String verb, path, body;
        Request(String verb, String path, String body) { this.verb = verb; this.path = path; this.body = body; }
        @Override public String toString() { return verb + " " + path + " " + body; }
    }

    private final ServerSocket server;
    private final Function<Request, byte[]> responder;
    final List<Request> requests = new CopyOnWriteArrayList<>();
    private final Thread acceptor;

    RecordedSbiServer(Function<Request, byte[]> responder) throws IOException {
        this.responder = responder;
        this.server = new ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"));
        acceptor = new Thread(this::acceptLoop, "recorded-sbi");
        acceptor.setDaemon(true);
        acceptor.start();
    }

    int port() { return server.getLocalPort(); }

    static byte[] fixture(String name) {
        try (InputStream in = RecordedSbiServer.class.getResourceAsStream("/mockmds/" + name)) {
            if (in == null) throw new IllegalArgumentException("no fixture " + name);
            return SbiHttp.readFully(in);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** The body of a recorded response (after the blank line), trimmed of MockMDS's trailing CRLFs. */
    static String fixtureBody(String name) {
        String all = new String(fixture(name), StandardCharsets.ISO_8859_1);
        return all.substring(all.indexOf("\r\n\r\n") + 4).trim();
    }

    private void acceptLoop() {
        while (!server.isClosed()) {
            try {
                Socket s = server.accept();
                Thread t = new Thread(() -> handle(s), "recorded-sbi-conn");
                t.setDaemon(true);
                t.start();
            } catch (IOException e) {
                return;
            }
        }
    }

    private void handle(Socket s) {
        try (s; InputStream in = s.getInputStream(); OutputStream out = s.getOutputStream()) {
            String requestLine = line(in);
            int contentLength = 0;
            String h;
            while ((h = line(in)) != null && !h.isEmpty()) {
                if (h.toLowerCase(Locale.ROOT).startsWith("content-length:")) contentLength = Integer.parseInt(h.substring(15).trim());
            }
            byte[] body = new byte[contentLength];
            int off = 0;
            while (off < contentLength) { int n = in.read(body, off, contentLength - off); if (n < 0) break; off += n; }
            String[] rl = requestLine == null ? new String[] {"", ""} : requestLine.split(" ");
            Request req = new Request(rl[0], rl.length > 1 ? rl[1] : "", new String(body, 0, off, StandardCharsets.UTF_8));
            requests.add(req);
            byte[] resp = responder.apply(req);
            if (resp != null) { out.write(resp); out.flush(); }
        } catch (IOException ignored) {
            // client went away
        }
    }

    private static String line(InputStream in) throws IOException {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        int c;
        while ((c = in.read()) >= 0 && c != '\n') if (c != '\r') b.write(c);
        if (c < 0 && b.size() == 0) return null;
        return b.toString(StandardCharsets.ISO_8859_1);
    }

    @Override public void close() throws IOException { server.close(); }
}
