package com.watchwise.watchwise_api.common.tmdb;

import com.watchwise.watchwise_api.common.exception.BadRequestException;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.net.URI;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.client.ExpectedCount.once;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.headerDoesNotExist;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;

class TmdbImageTransportTest {
    private final RestClient.Builder builder = RestClient.builder();
    private final MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
    private final TmdbImageTransport transport = new TmdbImageTransport(builder.build());

    @Test
    void relativePathUsesOnlyTmdbImageHostWithoutApiAuthorization() {
        server.expect(once(), requestTo(URI.create("https://image.tmdb.org/t/p/w500/poster.jpg")))
                .andExpect(headerDoesNotExist(HttpHeaders.AUTHORIZATION))
                .andRespond(withStatus(HttpStatus.OK).contentType(MediaType.IMAGE_JPEG).body(new byte[] {1, 2, 3}));

        assertArrayEquals(new byte[] {1, 2, 3}, transport.fetch("/poster.jpg").body());
        server.verify();
    }

    @Test
    void rejectsRedirectWithoutRequestingItsLocation() {
        server.expect(once(), requestTo("https://image.tmdb.org/t/p/w500/poster.png"))
                .andRespond(withStatus(HttpStatus.FOUND).header(HttpHeaders.LOCATION, "https://evil.example/poster.png"));

        assertThrows(BadRequestException.class, () -> transport.fetch("/poster.png"));
        server.verify();
    }

    @Test
    void rejectsOversizedBodyAndUnsupportedType() {
        server.expect(once(), requestTo("https://image.tmdb.org/t/p/w500/poster.png"))
                .andRespond(withStatus(HttpStatus.OK).contentType(MediaType.IMAGE_PNG)
                        .body(new byte[TmdbImageTransport.MAX_BYTES + 1]));
        server.expect(once(), requestTo("https://image.tmdb.org/t/p/w500/poster.png"))
                .andRespond(withStatus(HttpStatus.OK).contentType(MediaType.TEXT_HTML).body(new byte[] {1}));

        assertThrows(BadRequestException.class, () -> transport.fetch("/poster.png"));
        assertThrows(BadRequestException.class, () -> transport.fetch("/poster.png"));
        server.verify();
    }

    @Test
    void rejectsNonTmdbHostBeforeNetwork() {
        assertThrows(BadRequestException.class, () -> transport.fetch("https://evil.example/poster.png"));
        server.verify();
    }

    @Test
    void mapsMissingImageToTypedNotFoundError() {
        server.expect(once(), requestTo("https://image.tmdb.org/t/p/w500/missing.jpg"))
                .andRespond(withStatus(HttpStatus.NOT_FOUND));

        assertThrows(HttpClientErrorException.NotFound.class, () -> transport.fetch("/missing.jpg"));
        server.verify();
    }

    @Test
    void mapsOtherUpstreamErrorsToRestClientException() {
        server.expect(once(), requestTo("https://image.tmdb.org/t/p/w500/poster.jpg"))
                .andRespond(withStatus(HttpStatus.BAD_GATEWAY));

        assertThrows(RestClientException.class, () -> transport.fetch("/poster.jpg"));
        server.verify();
    }
}
