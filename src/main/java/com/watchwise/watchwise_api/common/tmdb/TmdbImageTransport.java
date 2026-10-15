package com.watchwise.watchwise_api.common.tmdb;

import com.watchwise.watchwise_api.common.exception.BadRequestException;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClientException;

import java.io.InputStream;
import java.net.URI;
import org.springframework.web.client.RestClient;

@Component
public class TmdbImageTransport {
    public static final int MAX_BYTES = 5 * 1024 * 1024;
    private final RestClient client;

    TmdbImageTransport(@Qualifier("tmdbImageRestClient") RestClient client) {
        this.client = client;
    }

    public TmdbImageBytes fetch(String normalizedPath) {
        normalizedPath = TmdbClient.normalizeImagePath(normalizedPath);
        URI uri = URI.create(normalizedPath.startsWith("https://") ? normalizedPath
                : "https://image.tmdb.org/t/p/w500" + normalizedPath);
        try {
            return client.get().uri(uri).exchange((request, response) -> {
                int statusCode = response.getStatusCode().value();
                if (statusCode == 404) {
                    throw HttpClientErrorException.create(org.springframework.http.HttpStatus.NOT_FOUND,
                            "Image not found", null, null, null);
                }
                if (statusCode >= 300 && statusCode < 400) {
                    throw new BadRequestException("TMDB image redirects are not allowed");
                }
                if (statusCode != 200) {
                    throw new RestClientException("TMDB image request failed: " + statusCode);
                }
                String type = response.getHeaders().getFirst("Content-Type");
                MediaType mediaType;
                try {
                    mediaType = MediaType.parseMediaType(type == null ? "" : type);
                } catch (RuntimeException invalid) {
                    throw new BadRequestException("Unsupported TMDB image type");
                }
                if (!MediaType.IMAGE_JPEG.isCompatibleWith(mediaType)
                        && !MediaType.IMAGE_PNG.isCompatibleWith(mediaType)) {
                    throw new BadRequestException("Unsupported TMDB image type");
                }
                if (response.getHeaders().getContentLength() > MAX_BYTES) {
                    throw new BadRequestException("TMDB image is too large");
                }
                InputStream stream = response.getBody();
                if (stream == null) {
                    throw new RestClientException("TMDB image response has no body");
                }
                byte[] bytes = stream.readNBytes(MAX_BYTES + 1);
                if (bytes.length > MAX_BYTES) {
                    throw new BadRequestException("TMDB image is too large");
                }
                return new TmdbImageBytes(bytes, mediaType);
            });
        } catch (RestClientException failure) {
            throw failure;
        }
    }
}
