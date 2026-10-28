package com.watchwise.watchwise_api.common.config;

import com.watchwise.watchwise_api.common.exception.ApiError;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import tools.jackson.databind.ObjectMapper;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.Locale;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 20)
public class JsonRequestBodyLimitFilter extends OncePerRequestFilter {

    private final ObjectMapper objectMapper;
    private final long maximumBytes;

    public JsonRequestBodyLimitFilter(ObjectMapper objectMapper,
            @Value("${app.http.max-json-body-bytes:2097152}") long maximumBytes) {
        if (maximumBytes < 1 || maximumBytes >= Integer.MAX_VALUE) {
            throw new IllegalArgumentException("JSON body limit must be between 1 and Integer.MAX_VALUE - 1 bytes");
        }
        this.objectMapper = objectMapper;
        this.maximumBytes = maximumBytes;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (!isJson(request.getContentType())) {
            chain.doFilter(request, response);
            return;
        }

        long declaredLength = request.getContentLengthLong();
        if (declaredLength > maximumBytes) {
            writeTooLarge(request, response, declaredLength, false, true);
            return;
        }

        byte[] body = request.getInputStream().readNBytes((int) maximumBytes + 1);
        if (body.length > maximumBytes) {
            writeTooLarge(request, response, body.length, declaredLength < 0, false);
            return;
        }
        chain.doFilter(new CachedJsonRequest(request, body), response);
    }

    private void writeTooLarge(HttpServletRequest request, HttpServletResponse response, long receivedBytes,
            boolean lowerBound, boolean declaredLength) throws IOException {
        String sizeDescription = declaredLength
                ? "Content-Length declares " + formatBytes(receivedBytes)
                : "received " + (lowerBound ? "at least " : "") + formatBytes(receivedBytes);
        String message = "JSON request body exceeds the configured maximum of " + formatBytes(maximumBytes)
                + " (" + sizeDescription + ")";
        ApiError error = new ApiError(LocalDateTime.now(), HttpStatus.PAYLOAD_TOO_LARGE.value(),
                "Payload Too Large", message, request.getRequestURI());
        response.setStatus(HttpStatus.PAYLOAD_TOO_LARGE.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.getWriter().write(objectMapper.writeValueAsString(error));
    }

    private boolean isJson(String contentType) {
        if (contentType == null) return false;
        String normalized = contentType.toLowerCase(Locale.ROOT);
        return normalized.startsWith(MediaType.APPLICATION_JSON_VALUE)
                || normalized.contains("+json");
    }

    private String formatBytes(long bytes) {
        long mib = 1024L * 1024L;
        long kib = 1024L;
        if (bytes % mib == 0) return (bytes / mib) + " MiB";
        if (bytes % kib == 0) return (bytes / kib) + " KiB";
        return bytes + " bytes";
    }

    private static final class CachedJsonRequest extends HttpServletRequestWrapper {
        private final byte[] body;

        private CachedJsonRequest(HttpServletRequest request, byte[] body) {
            super(request);
            this.body = body;
        }

        @Override
        public int getContentLength() {
            return body.length;
        }

        @Override
        public long getContentLengthLong() {
            return body.length;
        }

        @Override
        public ServletInputStream getInputStream() {
            ByteArrayInputStream input = new ByteArrayInputStream(body);
            return new ServletInputStream() {
                @Override public int read() { return input.read(); }
                @Override public int read(byte[] bytes, int offset, int length) { return input.read(bytes, offset, length); }
                @Override public boolean isFinished() { return input.available() == 0; }
                @Override public boolean isReady() { return true; }
                @Override public void setReadListener(ReadListener listener) { }
            };
        }

        @Override
        public BufferedReader getReader() throws IOException {
            String encoding = getCharacterEncoding();
            Charset charset = encoding == null ? StandardCharsets.UTF_8 : Charset.forName(encoding);
            return new BufferedReader(new InputStreamReader(getInputStream(), charset));
        }
    }
}
