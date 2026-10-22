package com.watchwise.watchwise_api.platformimport.config;

import com.watchwise.watchwise_api.platformimport.service.ImportLimits;
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
import java.util.concurrent.Semaphore;

public final class ImportMultipartRequestGateFilter extends OncePerRequestFilter {
    private final Semaphore uploadSlots;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public ImportMultipartRequestGateFilter(ImportLimits importLimits, ObjectMapper objectMapper, Clock clock) {
        if (importLimits.maxConcurrentUploadsPerProcess() < 1) {
            throw new IllegalArgumentException("Import upload concurrency limit must be positive");
        }
        this.uploadSlots = new Semaphore(importLimits.maxConcurrentUploadsPerProcess());
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
        if (!uploadSlots.tryAcquire()) {
            response.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            response.setCharacterEncoding(StandardCharsets.UTF_8.name());
            objectMapper.writeValue(response.getOutputStream(), Map.of(
                    "timestamp", LocalDateTime.now(clock).toString(),
                    "status", HttpStatus.TOO_MANY_REQUESTS.value(),
                    "error", "Too Many Requests",
                    "message", "Too many concurrent import uploads",
                    "path", request.getRequestURI()));
            return;
        }
        try {
            filterChain.doFilter(request, response);
        } finally {
            uploadSlots.release();
        }
    }
}
