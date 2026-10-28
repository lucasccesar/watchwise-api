package com.watchwise.watchwise_api.platformimport.storage;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

public final class S3StubServer implements AutoCloseable {
    private static final int MAX_HEADER_LINE_BYTES = 16 * 1024;
    private static final int MAX_REQUEST_BODY_BYTES = 2 * 1024 * 1024;

    private final String bucket;
    private final ServerSocket listener;
    private final ExecutorService acceptor;
    private final ExecutorService workers;
    private final Map<String, StoredObject> objects = new ConcurrentHashMap<>();
    private final List<Request> requests = new CopyOnWriteArrayList<>();
    private final Map<String, ConcurrentLinkedQueue<Integer>> failures = new ConcurrentHashMap<>();
    private final AtomicBoolean disconnectNextPut = new AtomicBoolean();
    private final AtomicReference<Duration> delayNextGetBody = new AtomicReference<>();
    private final AtomicReference<Integer> truncateNextGetBody = new AtomicReference<>();
    private final AtomicBoolean closed = new AtomicBoolean();

    private S3StubServer(String bucket) throws IOException {
        this.bucket = bucket;
        this.listener = new ServerSocket();
        listener.bind(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0));
        this.acceptor = Executors.newSingleThreadExecutor(runnable -> daemonThread(runnable, "s3-stub-accept"));
        this.workers = Executors.newFixedThreadPool(4, runnable -> daemonThread(runnable, "s3-stub-request"));
        acceptor.execute(this::acceptRequests);
    }

    public static S3StubServer start(String bucket) throws IOException {
        if (bucket == null || bucket.isBlank()) throw new IllegalArgumentException("S3 test bucket is required");
        return new S3StubServer(bucket);
    }

    public URI endpoint() {
        return URI.create("http://127.0.0.1:" + listener.getLocalPort());
    }

    public String objectPath(String key) {
        return "/" + bucket + "/imports/" + key;
    }

    public void failNext(String method, String path, int status) {
        failNext(method, path, status, 1);
    }

    public void failNext(String method, String path, int status, int times) {
        if (times < 1) throw new IllegalArgumentException("Failure count must be positive");
        failures.computeIfAbsent(requestKey(method, path), ignored -> new ConcurrentLinkedQueue<>()).add(status);
        for (int index = 1; index < times; index++) {
            failures.get(requestKey(method, path)).add(status);
        }
    }

    public void disconnectAfterNextPut() {
        disconnectNextPut.set(true);
    }

    public void delayNextGetBody(Duration delay) {
        if (delay == null || delay.isNegative() || delay.isZero()) {
            throw new IllegalArgumentException("S3 response delay must be positive");
        }
        delayNextGetBody.set(delay);
    }

    public void truncateNextGetBody(int afterBytes) {
        if (afterBytes < 0) throw new IllegalArgumentException("Truncated response size cannot be negative");
        truncateNextGetBody.set(afterBytes);
    }

    public byte[] storedBody(String path) {
        StoredObject stored = objects.get(path);
        return stored == null ? null : stored.body().clone();
    }

    public boolean containsObject(String path) {
        return objects.containsKey(path);
    }

    public long requestCount(String method, String path) {
        return requests.stream().filter(request -> request.method().equals(method)
                && request.path().equals(path)).count();
    }

    public Map<String, String> lastHeaders(String method, String path) {
        for (int index = requests.size() - 1; index >= 0; index--) {
            Request request = requests.get(index);
            if (request.method().equals(method) && request.path().equals(path)) return request.headers();
        }
        return Map.of();
    }

    public List<Request> requests() {
        return List.copyOf(requests);
    }

    private void acceptRequests() {
        while (!closed.get()) {
            try {
                Socket socket = listener.accept();
                socket.setSoTimeout(5000);
                workers.execute(() -> handleConnection(socket));
            } catch (IOException exception) {
                if (!closed.get()) throw new IllegalStateException("S3 test server accept failed", exception);
            }
        }
    }

    private void handleConnection(Socket socket) {
        try (socket;
             BufferedInputStream input = new BufferedInputStream(socket.getInputStream());
             BufferedOutputStream output = new BufferedOutputStream(socket.getOutputStream())) {
            String requestLine = readLine(input);
            if (requestLine == null) return;
            String[] requestParts = requestLine.split(" ", 3);
            if (requestParts.length != 3) return;
            Map<String, String> headers = readHeaders(input);
            if ("100-continue".equalsIgnoreCase(headers.get("expect"))) {
                output.write("HTTP/1.1 100 Continue\r\n\r\n".getBytes(StandardCharsets.US_ASCII));
                output.flush();
            }
            int bodyLength = parseContentLength(headers.get("content-length"));
            byte[] body = input.readNBytes(bodyLength);
            if (body.length != bodyLength) return;

            String method = requestParts[0].toUpperCase(Locale.ROOT);
            String path = requestParts[1].split("\\?", 2)[0];
            Request request = new Request(method, path, headers, body);
            requests.add(request);
            Reply reply = respond(request);
            if (reply.disconnect()) return;
            writeResponse(output, method, reply);
        } catch (IOException exception) {
        }
    }

    private Reply respond(Request request) throws IOException {
        ConcurrentLinkedQueue<Integer> pending = failures.get(requestKey(request.method(), request.path()));
        if (pending != null) {
            Integer status = pending.poll();
            if (status != null) return errorReply(status, errorCode(status));
        }
        return switch (request.method()) {
            case "PUT" -> put(request);
            case "GET" -> get(request);
            case "HEAD" -> head(request);
            case "DELETE" -> delete(request);
            default -> errorReply(405, "MethodNotAllowed");
        };
    }

    private Reply put(Request request) {
        StoredObject existing = objects.get(request.path());
        if ("*".equals(request.headers().get("if-none-match")) && existing != null) {
            return errorReply(412, "PreconditionFailed");
        }
        Map<String, String> metadata = new LinkedHashMap<>();
        request.headers().forEach((name, value) -> {
            if (name.startsWith("x-amz-meta-")) metadata.put(name.substring("x-amz-meta-".length()), value);
        });
        byte[] body = request.body().clone();
        String etag = "\"stub-" + Integer.toUnsignedString(java.util.Arrays.hashCode(body), 16) + "\"";
        objects.put(request.path(), new StoredObject(body, Map.copyOf(metadata), etag));
        if (disconnectNextPut.compareAndSet(true, false)) return Reply.disconnected();
        return Reply.empty(200, Map.of("ETag", etag));
    }

    private Reply get(Request request) {
        StoredObject stored = objects.get(request.path());
        if (stored == null) return errorReply(404, "NoSuchKey");
        Integer truncateAfter = truncateNextGetBody.getAndSet(null);
        return new Reply(200, objectHeaders(stored), stored.body().clone(), false,
                delayNextGetBody.getAndSet(null), truncateAfter == null ? -1 : truncateAfter);
    }

    private Reply head(Request request) {
        StoredObject stored = objects.get(request.path());
        return stored == null ? errorReply(404, "NoSuchKey") : Reply.empty(200, objectHeaders(stored));
    }

    private Reply delete(Request request) {
        objects.remove(request.path());
        return Reply.empty(204, Map.of());
    }

    private Map<String, String> objectHeaders(StoredObject stored) {
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("ETag", stored.etag());
        headers.put("Content-Length", Integer.toString(stored.body().length));
        stored.metadata().forEach((name, value) -> headers.put("x-amz-meta-" + name, value));
        return headers;
    }

    private Reply errorReply(int status, String code) {
        String body = "<Error><Code>" + code + "</Code><Message>stub error</Message>"
                + "<RequestId>s3-stub</RequestId></Error>";
        return new Reply(status, Map.of("Content-Type", "application/xml"),
                body.getBytes(StandardCharsets.UTF_8), false, null, -1);
    }

    private void writeResponse(BufferedOutputStream output, String requestMethod, Reply reply) throws IOException {
        String declaredLength = reply.headers().entrySet().stream()
                .filter(header -> header.getKey().equalsIgnoreCase("Content-Length"))
                .map(Map.Entry::getValue).findFirst().orElse(null);
        int contentLength = requestMethod.equals("HEAD")
                ? declaredLength == null ? 0 : Integer.parseInt(declaredLength)
                : reply.body().length;
        output.write(("HTTP/1.1 " + reply.status() + " " + reason(reply.status()) + "\r\n")
                .getBytes(StandardCharsets.US_ASCII));
        for (Map.Entry<String, String> header : reply.headers().entrySet()) {
            if (header.getKey().equalsIgnoreCase("Content-Length")) continue;
            output.write((header.getKey() + ": " + header.getValue() + "\r\n")
                    .getBytes(StandardCharsets.ISO_8859_1));
        }
        output.write(("Content-Length: " + contentLength + "\r\nConnection: close\r\n\r\n")
                .getBytes(StandardCharsets.US_ASCII));
        output.flush();
        if (requestMethod.equals("HEAD") || reply.body().length == 0) return;
        if (reply.delay() != null) {
            try {
                TimeUnit.NANOSECONDS.sleep(reply.delay().toNanos());
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new IOException("S3 test response was interrupted", exception);
            }
        }
        int bodyBytes = reply.truncateAfterBytes() < 0
                ? reply.body().length : Math.min(reply.body().length, reply.truncateAfterBytes());
        output.write(reply.body(), 0, bodyBytes);
        output.flush();
    }

    private Map<String, String> readHeaders(BufferedInputStream input) throws IOException {
        Map<String, String> headers = new LinkedHashMap<>();
        for (int count = 0; count < 128; count++) {
            String line = readLine(input);
            if (line == null || line.isEmpty()) return Map.copyOf(headers);
            int separator = line.indexOf(':');
            if (separator < 1) throw new IOException("Malformed S3 test request header");
            headers.put(line.substring(0, separator).trim().toLowerCase(Locale.ROOT),
                    line.substring(separator + 1).trim());
        }
        throw new IOException("S3 test request has too many headers");
    }

    private String readLine(BufferedInputStream input) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        for (int next; (next = input.read()) != -1;) {
            if (next == '\n') {
                byte[] line = bytes.toByteArray();
                int length = line.length > 0 && line[line.length - 1] == '\r' ? line.length - 1 : line.length;
                return new String(line, 0, length, StandardCharsets.ISO_8859_1);
            }
            bytes.write(next);
            if (bytes.size() > MAX_HEADER_LINE_BYTES) throw new IOException("S3 test request line is too long");
        }
        return bytes.size() == 0 ? null : bytes.toString(StandardCharsets.ISO_8859_1);
    }

    private int parseContentLength(String value) throws IOException {
        if (value == null) return 0;
        try {
            int length = Integer.parseInt(value);
            if (length < 0 || length > MAX_REQUEST_BODY_BYTES) {
                throw new IOException("S3 test request body is too large");
            }
            return length;
        } catch (NumberFormatException exception) {
            throw new IOException("S3 test request has an invalid content length", exception);
        }
    }

    private String requestKey(String method, String path) {
        return method.toUpperCase(Locale.ROOT) + " " + path;
    }

    private String errorCode(int status) {
        return switch (status) {
            case 403 -> "AccessDenied";
            case 404 -> "NoSuchKey";
            case 409 -> "Conflict";
            case 412 -> "PreconditionFailed";
            case 429 -> "SlowDown";
            case 503 -> "ServiceUnavailable";
            default -> "InternalError";
        };
    }

    private String reason(int status) {
        return switch (status) {
            case 200 -> "OK";
            case 204 -> "No Content";
            case 403 -> "Forbidden";
            case 404 -> "Not Found";
            case 405 -> "Method Not Allowed";
            case 409 -> "Conflict";
            case 412 -> "Precondition Failed";
            case 429 -> "Too Many Requests";
            case 500 -> "Internal Server Error";
            case 503 -> "Service Unavailable";
            default -> "Error";
        };
    }

    private Thread daemonThread(Runnable runnable, String name) {
        Thread thread = new Thread(runnable, name);
        thread.setDaemon(true);
        return thread;
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) return;
        try {
            listener.close();
        } catch (IOException ignored) {
        }
        acceptor.shutdownNow();
        workers.shutdownNow();
        try {
            acceptor.awaitTermination(1, TimeUnit.SECONDS);
            workers.awaitTermination(1, TimeUnit.SECONDS);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        }
    }

    public record Request(String method, String path, Map<String, String> headers, byte[] body) { }

    private record StoredObject(byte[] body, Map<String, String> metadata, String etag) { }

    private record Reply(int status, Map<String, String> headers, byte[] body,
                         boolean disconnect, Duration delay, int truncateAfterBytes) {
        static Reply empty(int status, Map<String, String> headers) {
            return new Reply(status, headers, new byte[0], false, null, -1);
        }

        static Reply disconnected() {
            return new Reply(0, Map.of(), new byte[0], true, null, -1);
        }
    }
}
