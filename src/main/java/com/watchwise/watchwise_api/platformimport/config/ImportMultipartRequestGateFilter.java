package com.watchwise.watchwise_api.platformimport.config;

import com.watchwise.watchwise_api.common.exception.TooManyRequestsException;
import com.watchwise.watchwise_api.platformimport.storage.ImportTemporaryFiles;
import com.watchwise.watchwise_api.platformimport.storage.MultipartUploadReservation;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.MediaType;
import org.springframework.http.HttpStatus;
import org.springframework.web.filter.OncePerRequestFilter;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.Locale;
import java.util.Map;

public final class ImportMultipartRequestGateFilter extends OncePerRequestFilter {
    private final ImportTemporaryFiles temporaryFiles;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public ImportMultipartRequestGateFilter(ImportTemporaryFiles temporaryFiles,
                                            ObjectMapper objectMapper, Clock clock) {
        this.temporaryFiles = temporaryFiles;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI();
        String contextPath = request.getContextPath();
        if (contextPath != null && !contextPath.isEmpty() && path.startsWith(contextPath)) {
            path = path.substring(contextPath.length());
        }
        String contentType = request.getContentType();
        boolean multipart = contentType != null
                && contentType.toLowerCase(Locale.ROOT).startsWith(MediaType.MULTIPART_FORM_DATA_VALUE)
                && (contentType.length() == MediaType.MULTIPART_FORM_DATA_VALUE.length()
                || contentType.charAt(MediaType.MULTIPART_FORM_DATA_VALUE.length()) == ';'
                || Character.isWhitespace(contentType.charAt(MediaType.MULTIPART_FORM_DATA_VALUE.length())));
        return !"POST".equals(request.getMethod()) || !"/imports".equals(path) || !multipart;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        MultipartUploadReservation reservation;
        try {
            reservation = temporaryFiles.admitMultipart();
        } catch (TooManyRequestsException exception) {
            writeTooManyRequests(request, response);
            return;
        }
        try {
            filterChain.doFilter(request, response);
        } finally {
            reservation.close();
        }
    }

    private void writeTooManyRequests(HttpServletRequest request, HttpServletResponse response) throws IOException {
        response.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        objectMapper.writeValue(response.getOutputStream(), Map.of(
                "timestamp", LocalDateTime.now(clock).toString(),
                "status", HttpStatus.TOO_MANY_REQUESTS.value(),
                "error", "Too Many Requests",
                "message", "Too many concurrent import uploads",
                "path", request.getRequestURI()));
    }
}
