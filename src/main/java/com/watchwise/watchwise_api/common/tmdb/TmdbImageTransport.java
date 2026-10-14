package com.watchwise.watchwise_api.common.tmdb;

import com.watchwise.watchwise_api.common.exception.BadRequestException;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClientException;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

@Component
public class TmdbImageTransport {
    public static final int MAX_BYTES = 5 * 1024 * 1024;
    private final HttpClient client;

    public TmdbImageTransport() {
        this(HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER)
                .connectTimeout(Duration.ofSeconds(5)).build());
    }

    TmdbImageTransport(HttpClient client) {
        this.client = client;
    }

    public TmdbImageBytes fetch(String normalizedPath) {
        normalizedPath = TmdbClient.normalizeImagePath(normalizedPath);
        URI uri = URI.create(normalizedPath.startsWith("https://") ? normalizedPath
                : "https://image.tmdb.org/t/p/w500" + normalizedPath);
        try {
            HttpRequest request = HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(10)).GET().build();
            HttpResponse<InputStream> response = client.send(request, HttpResponse.BodyHandlers.ofInputStream());
            try (InputStream stream = response.body()) {
                if (response.statusCode() == 404) {
                    throw HttpClientErrorException.create(org.springframework.http.HttpStatus.NOT_FOUND,
                            "Image not found", null, null, null);
                }
                if (response.statusCode() >= 300 && response.statusCode() < 400) {
                    throw new BadRequestException("TMDB image redirects are not allowed");
                }
                if (response.statusCode() != 200) {
                    throw new RestClientException("TMDB image request failed: " + response.statusCode());
                }
                String type = response.headers().firstValue("Content-Type").orElse("");
                MediaType mediaType;
                try {
                    mediaType = MediaType.parseMediaType(type);
                } catch (RuntimeException invalid) {
                    throw new BadRequestException("Unsupported TMDB image type");
                }
                if (!MediaType.IMAGE_JPEG.isCompatibleWith(mediaType)
                        && !MediaType.IMAGE_PNG.isCompatibleWith(mediaType)) {
                    throw new BadRequestException("Unsupported TMDB image type");
                }
                if (response.headers().firstValueAsLong("Content-Length").orElse(0) > MAX_BYTES) {
                    throw new BadRequestException("TMDB image is too large");
                }
                byte[] bytes = stream.readNBytes(MAX_BYTES + 1);
                if (bytes.length > MAX_BYTES) {
                    throw new BadRequestException("TMDB image is too large");
                }
                return new TmdbImageBytes(bytes, mediaType);
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new RestClientException("TMDB image request interrupted", interrupted);
        } catch (IOException failure) {
            throw new RestClientException("TMDB image request failed", failure);
        }
    }
}
