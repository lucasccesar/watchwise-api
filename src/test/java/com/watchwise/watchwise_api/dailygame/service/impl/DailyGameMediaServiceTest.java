package com.watchwise.watchwise_api.dailygame.service.impl;

import com.watchwise.watchwise_api.common.exception.BadRequestException;
import com.watchwise.watchwise_api.common.exception.NotFoundException;
import com.github.benmanes.caffeine.cache.Cache;
import com.watchwise.watchwise_api.common.tmdb.TmdbClient;
import com.watchwise.watchwise_api.common.tmdb.TmdbImageBytes;
import com.watchwise.watchwise_api.common.tmdb.TmdbLookupResult;
import com.watchwise.watchwise_api.dailygame.dto.DailyGameMediaRefDTO;
import com.watchwise.watchwise_api.dailygame.media.DailyGameImageVariant;
import com.watchwise.watchwise_api.dailygame.media.DailyGameMediaSlot;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.lang.reflect.Constructor;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class DailyGameMediaServiceTest {
    private final UUID user = UUID.randomUUID();
    private final UUID challenge = UUID.randomUUID();
    private final TmdbClient tmdb = mock(TmdbClient.class);
    private final MutableClock clock = new MutableClock();
    private DailyGameMediaServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new DailyGameMediaServiceImpl(tmdb, clock);
    }

    @Test
    void openClueUsesOpaqueGrantAndTransformsPixels() throws Exception {
        byte[] source = image(160, 160, "png");
        when(tmdb.getImageBytes("/clue.png")).thenReturn(new TmdbLookupResult.Found<>(new TmdbImageBytes(source, MediaType.IMAGE_PNG)));
        DailyGameMediaRefDTO ref = service.issue(user, challenge, DailyGameMediaSlot.CLUE, DailyGameImageVariant.BLUR_26, "/clue.png");
        assertTrue(ref.url().startsWith("/games/play/media/"));
        assertFalse(ref.url().contains("clue"));
        assertFalse(ref.url().contains(user.toString()));
        assertFalse(ref.url().contains(challenge.toString()));
        byte[] transformed = service.fetch(user, token(ref)).body();
        assertNotEquals(java.util.Arrays.toString(source), java.util.Arrays.toString(transformed));
        assertNotNull(ImageIO.read(new java.io.ByteArrayInputStream(transformed)));
    }

    @Test
    void originalGrantReturnsOriginalBytes() throws Exception {
        byte[] source = image(80, 80, "png");
        when(tmdb.getImageBytes("/frame.png")).thenReturn(new TmdbLookupResult.Found<>(new TmdbImageBytes(source, MediaType.IMAGE_PNG)));
        var ref = service.issue(user, challenge, DailyGameMediaSlot.FRAME_0, DailyGameImageVariant.ORIGINAL, "/frame.png");
        assertArrayEquals(source, service.fetch(user, token(ref)).body());
    }

    @Test
    void expiredAndForeignGrantsAreRejectedBeforeImageLookup() {
        var ref = service.issue(user, challenge, DailyGameMediaSlot.CLUE, DailyGameImageVariant.BLUR_9, "/clue.png");
        assertThrows(NotFoundException.class, () -> service.fetch(UUID.randomUUID(), token(ref)));
        clock.advanceSeconds(601);
        assertThrows(NotFoundException.class, () -> service.fetch(user, token(ref)));
        verifyNoInteractions(tmdb);
    }

    @Test
    @SuppressWarnings("unchecked")
    void sourceFingerprintMismatchRejectsGrantBeforeImageLookup() throws Exception {
        var ref = service.issue(user, challenge, DailyGameMediaSlot.CLUE, DailyGameImageVariant.BLUR_9, "/clue.png");
        Cache<String, Object> grants = (Cache<String, Object>) org.springframework.test.util.ReflectionTestUtils.getField(service, "grants");
        Constructor<?> constructor = grants.getIfPresent(token(ref)).getClass().getDeclaredConstructors()[0];
        constructor.setAccessible(true);
        grants.put(token(ref), constructor.newInstance(user, challenge, DailyGameMediaSlot.CLUE,
                DailyGameImageVariant.BLUR_9, "/swapped.png", "wrong-fingerprint",
                clock.instant().plusSeconds(600)));
        assertThrows(NotFoundException.class, () -> service.fetch(user, token(ref)));
        verifyNoInteractions(tmdb);
    }

    @Test
    void invalidSourcePathsAreRejected() {
        for (String path : new String[] {"http://image.tmdb.org/a.png", "https://evil.example/a.png",
                "https://image.tmdb.org/a.png?q=x", "https://image.tmdb.org/a.png#x", "/a b.png",
                "//evil.example/a.png", "https://image.tmdb.org/"}) {
            assertThrows(BadRequestException.class, () -> service.issue(user, challenge,
                    DailyGameMediaSlot.CLUE, DailyGameImageVariant.BLUR_26, path), path);
        }
    }

    @Test
    void acceptsRelativeTmdbImagePathWithoutLeadingSlash() {
        var ref = service.issue(user, challenge, DailyGameMediaSlot.CLUE,
                DailyGameImageVariant.BLUR_26, "poster.jpg");
        assertTrue(ref.url().startsWith("/games/play/media/"));
    }

    @Test
    void invalidMediaOversizedBytesAndCorruptImageAreRejected() throws Exception {
        for (TmdbImageBytes result : new TmdbImageBytes[] {
                new TmdbImageBytes(image(20, 20, "png"), MediaType.IMAGE_GIF),
                new TmdbImageBytes(new byte[5 * 1024 * 1024 + 1], MediaType.IMAGE_PNG),
                new TmdbImageBytes(new byte[] {1, 2, 3}, MediaType.IMAGE_PNG)}) {
            when(tmdb.getImageBytes("/bad.png")).thenReturn(new TmdbLookupResult.Found<>(result));
            var ref = service.issue(user, challenge, DailyGameMediaSlot.CLUE, DailyGameImageVariant.BLUR_20, "/bad.png");
            assertThrows(BadRequestException.class, () -> service.fetch(user, token(ref)));
        }
    }

    @Test
    void oversizedPixelsAreRejected() throws Exception {
        byte[] source = image(4200, 2, "png");
        when(tmdb.getImageBytes("/wide.png")).thenReturn(new TmdbLookupResult.Found<>(new TmdbImageBytes(source, MediaType.IMAGE_PNG)));
        var ref = service.issue(user, challenge, DailyGameMediaSlot.CLUE, DailyGameImageVariant.BLUR_20, "/wide.png");
        assertThrows(BadRequestException.class, () -> service.fetch(user, token(ref)));
    }

    @Test
    void rejectsImageWhoseBytesDisagreeWithDeclaredMediaType() throws Exception {
        byte[] jpeg = image(80, 80, "jpeg");
        when(tmdb.getImageBytes("/wrong.png")).thenReturn(new TmdbLookupResult.Found<>(
                new TmdbImageBytes(jpeg, MediaType.IMAGE_PNG)));
        var ref = service.issue(user, challenge, DailyGameMediaSlot.FRAME_0, DailyGameImageVariant.ORIGINAL, "/wrong.png");
        assertThrows(BadRequestException.class, () -> service.fetch(user, token(ref)));
    }

    @Test
    void authorizedGrantsShareTransformedBytesButForeignUserCannotReadCache() throws Exception {
        byte[] source = image(80, 80, "png");
        when(tmdb.getImageBytes("/same.png")).thenReturn(new TmdbLookupResult.Found<>(new TmdbImageBytes(source, MediaType.IMAGE_PNG)));
        var first = service.issue(user, challenge, DailyGameMediaSlot.CLUE, DailyGameImageVariant.BLUR_5, "/same.png");
        var second = service.issue(user, challenge, DailyGameMediaSlot.CLUE, DailyGameImageVariant.BLUR_5, "/same.png");
        byte[] one = service.fetch(user, token(first)).body();
        assertThrows(NotFoundException.class, () -> service.fetch(UUID.randomUUID(), token(second)));
        assertArrayEquals(one, service.fetch(user, token(second)).body());
        verify(tmdb, times(1)).getImageBytes("/same.png");
    }

    private static String token(DailyGameMediaRefDTO ref) {
        return ref.url().substring(ref.url().lastIndexOf('/') + 1);
    }

    private static byte[] image(int width, int height, String format) throws Exception {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < height; y++) for (int x = 0; x < width; x++)
            image.setRGB(x, y, ((x / 8 + y / 8) & 1) == 0 ? Color.RED.getRGB() : Color.BLUE.getRGB());
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(image, format, out);
        return out.toByteArray();
    }

    private static final class MutableClock extends Clock {
        private Instant now = Instant.parse("2026-10-14T00:00:00Z");
        @Override public ZoneId getZone() { return ZoneId.of("UTC"); }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }
        void advanceSeconds(long seconds) { now = now.plusSeconds(seconds); }
    }
}
