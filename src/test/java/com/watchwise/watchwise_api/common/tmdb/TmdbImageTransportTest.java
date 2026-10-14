package com.watchwise.watchwise_api.common.tmdb;

import com.watchwise.watchwise_api.common.exception.BadRequestException;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class TmdbImageTransportTest {
    private final HttpClient client = mock(HttpClient.class);
    private final TmdbImageTransport transport = new TmdbImageTransport(client);

    @Test
    void relativePathUsesOnlyTmdbImageHost() throws Exception {
        when(client.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                .thenAnswer(invocation -> {
                    HttpRequest request = invocation.getArgument(0);
                    assertEquals(URI.create("https://image.tmdb.org/t/p/w500/poster.jpg"), request.uri());
                    return response(200, "image/jpeg", new byte[] {1, 2, 3});
                });
        assertArrayEquals(new byte[] {1, 2, 3}, transport.fetch("/poster.jpg").body());
    }

    @Test
    void rejectsRedirectWithoutFollowingIt() throws Exception {
        HttpResponse<java.io.InputStream> redirect = response(302, "image/png", new byte[0]);
        when(client.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                .thenReturn(redirect);
        assertThrows(BadRequestException.class, () -> transport.fetch("/poster.png"));
        verify(client, times(1)).send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class));
    }

    @Test
    void rejectsOversizedBodyAndUnsupportedType() throws Exception {
        HttpResponse<java.io.InputStream> oversized = response(200, "image/png", new byte[TmdbImageTransport.MAX_BYTES + 1]);
        HttpResponse<java.io.InputStream> unsupported = response(200, "text/html", new byte[] {1});
        when(client.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                .thenReturn(oversized, unsupported);
        assertThrows(BadRequestException.class, () -> transport.fetch("/poster.png"));
        assertThrows(BadRequestException.class, () -> transport.fetch("/poster.png"));
    }

    @Test
    void rejectsNonTmdbHostBeforeNetwork() {
        assertThrows(BadRequestException.class, () -> transport.fetch("https://evil.example/poster.png"));
        verifyNoInteractions(client);
    }

    @SuppressWarnings("unchecked")
    private static HttpResponse<java.io.InputStream> response(int status, String type, byte[] body) {
        HttpResponse<java.io.InputStream> response = mock(HttpResponse.class);
        when(response.statusCode()).thenReturn(status);
        when(response.headers()).thenReturn(HttpHeaders.of(Map.of("Content-Type", java.util.List.of(type)), (a, b) -> true));
        when(response.body()).thenReturn(new ByteArrayInputStream(body));
        return response;
    }
}
