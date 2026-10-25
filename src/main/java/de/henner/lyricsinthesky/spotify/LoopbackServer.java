package de.henner.lyricsinthesky.spotify;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Function;

/**
 * Tiny one-shot web server on 127.0.0.1 that receives the browser redirect after the Spotify login. Written
 * on plain sockets because the Java runtime shipped with Minecraft does not include the JDK's HTTP server.
 */
final class LoopbackServer implements AutoCloseable {
    private final ServerSocket socket;

    private LoopbackServer(ServerSocket socket) {
        this.socket = socket;
    }

    static LoopbackServer open(int port) throws IOException {
        return new LoopbackServer(new ServerSocket(port, 8, InetAddress.getLoopbackAddress()));
    }

    int port() {
        return socket.getLocalPort();
    }

    /**
     * Waits for {@code GET /callback?...} and answers it with the page {@code page} builds from the query.
     * Other requests (such as the browser asking for a favicon) get a 404 and are ignored.
     *
     * @return the query parameters of the callback
     * @throws SocketTimeoutException when nothing arrives in time
     */
    Map<String, String> awaitCallback(Duration timeout, Function<Map<String, String>, String> page) throws IOException {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (true) {
            long remainingMs = (deadline - System.nanoTime()) / 1_000_000L;
            if (remainingMs <= 0) {
                throw new SocketTimeoutException("no callback within " + timeout.toSeconds() + " s");
            }
            socket.setSoTimeout((int) Math.min(Integer.MAX_VALUE, remainingMs));
            try (Socket client = socket.accept()) {
                client.setSoTimeout(5_000);
                BufferedReader reader = new BufferedReader(new InputStreamReader(client.getInputStream(), StandardCharsets.UTF_8));
                String requestLine = reader.readLine();
                if (requestLine == null) {
                    continue;
                }
                // Read the headers to the end: closing with unread data makes the OS reset the connection, and the
                // browser would show an error instead of our page.
                String header;
                while ((header = reader.readLine()) != null && !header.isEmpty()) {
                    // headers are not needed
                }
                String[] parts = requestLine.split(" ");
                String target = parts.length > 1 ? parts[1] : "/";
                int question = target.indexOf('?');
                String path = question < 0 ? target : target.substring(0, question);
                if (!"GET".equals(parts[0]) || !"/callback".equals(path)) {
                    respond(client.getOutputStream(), "404 Not Found", "");
                    continue;
                }
                Map<String, String> query = parseQuery(question < 0 ? "" : target.substring(question + 1));
                respond(client.getOutputStream(), "200 OK", page.apply(query));
                return query;
            }
        }
    }

    static Map<String, String> parseQuery(String query) {
        Map<String, String> result = new LinkedHashMap<>();
        for (String pair : query.split("&")) {
            if (pair.isEmpty()) {
                continue;
            }
            int equals = pair.indexOf('=');
            String key = equals < 0 ? pair : pair.substring(0, equals);
            String value = equals < 0 ? "" : pair.substring(equals + 1);
            result.put(URLDecoder.decode(key, StandardCharsets.UTF_8), URLDecoder.decode(value, StandardCharsets.UTF_8));
        }
        return result;
    }

    private static void respond(OutputStream out, String status, String html) throws IOException {
        byte[] body = html.getBytes(StandardCharsets.UTF_8);
        String head = "HTTP/1.1 " + status + "\r\n"
                + "Content-Type: text/html; charset=utf-8\r\n"
                + "Content-Length: " + body.length + "\r\n"
                + "Cache-Control: no-store\r\n"
                + "Connection: close\r\n\r\n";
        out.write(head.getBytes(StandardCharsets.US_ASCII));
        out.write(body);
        out.flush();
    }

    @Override
    public void close() throws IOException {
        socket.close();
    }
}
