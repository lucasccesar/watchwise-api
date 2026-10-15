package com.watchwise.watchwise_api.dailygame.service.impl;

import com.watchwise.watchwise_api.common.exception.BadRequestException;
import com.watchwise.watchwise_api.common.exception.NotFoundException;
import com.watchwise.watchwise_api.common.tmdb.TmdbClient;
import com.watchwise.watchwise_api.common.tmdb.TmdbImageBytes;
import com.watchwise.watchwise_api.common.tmdb.TmdbLookupResult;
import com.watchwise.watchwise_api.dailygame.dto.DailyGameMediaRefDTO;
import com.watchwise.watchwise_api.dailygame.entity.DailyChallenge;
import com.watchwise.watchwise_api.dailygame.media.DailyGameImageVariant;
import com.watchwise.watchwise_api.dailygame.media.DailyGameMediaSlot;
import com.watchwise.watchwise_api.dailygame.repository.DailyChallengeRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.http.MediaType;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicReference;
import java.util.zip.CRC32;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class DailyGameMediaServiceTest {
    private final UUID user = UUID.randomUUID();
    private final UUID challenge = UUID.randomUUID();
    private final TmdbClient tmdb = mock(TmdbClient.class);
    private final DailyChallengeRepository challenges = mock(DailyChallengeRepository.class);
    private final AtomicReference<DailyChallenge> currentChallenge = new AtomicReference<>();
    private final MutableClock clock = new MutableClock();
    private DailyGameMediaServiceImpl service;

    @BeforeEach
    void setUp() {
        when(challenges.findById(challenge)).thenAnswer(ignored -> Optional.ofNullable(currentChallenge.get()));
        service = new DailyGameMediaServiceImpl(tmdb, challenges, clock);
    }

    @Test
    void openClueUsesOpaqueGrantAndTransformsPixels() throws Exception {
        byte[] source = image(160, 160, "png");
        when(tmdb.getImageBytes("/clue.png")).thenReturn(new TmdbLookupResult.Found<>(new TmdbImageBytes(source, MediaType.IMAGE_PNG)));
        DailyGameMediaRefDTO ref = issue(DailyGameMediaSlot.CLUE, DailyGameImageVariant.BLUR_26, "/clue.png");
        assertTrue(ref.url().startsWith("/games/play/media/"));
        assertFalse(ref.url().contains("clue"));
        assertFalse(ref.url().contains(user.toString()));
        assertFalse(ref.url().contains(challenge.toString()));
        byte[] transformed = service.fetch(user, token(ref)).body();
        assertNotEquals(java.util.Arrays.toString(source), java.util.Arrays.toString(transformed));
        assertNotNull(ImageIO.read(new java.io.ByteArrayInputStream(transformed)));
    }

    @Test
    void originalGrantReencodesAtSourceDimensionsWithoutMetadata() throws Exception {
        byte[] source = metadataBearingPng(image(80, 80, "png"));
        when(tmdb.getImageBytes("/frame.png")).thenReturn(new TmdbLookupResult.Found<>(new TmdbImageBytes(source, MediaType.IMAGE_PNG)));
        var ref = issue(DailyGameMediaSlot.FRAME_0, DailyGameImageVariant.ORIGINAL, "/frame.png");
        var response = service.fetch(user, token(ref));
        byte[] output = response.body();
        assertEquals(MediaType.IMAGE_PNG, response.mediaType());
        assertFalse(Arrays.equals(source, output));
        assertTrue(new String(source, StandardCharsets.ISO_8859_1).contains("private-fixture-metadata"));
        assertFalse(new String(output, StandardCharsets.ISO_8859_1).contains("private-fixture-metadata"));
        BufferedImage expected = ImageIO.read(new java.io.ByteArrayInputStream(source));
        BufferedImage actual = ImageIO.read(new java.io.ByteArrayInputStream(output));
        assertNotNull(actual);
        assertEquals(80, actual.getWidth());
        assertEquals(80, actual.getHeight());
        for (int y = 0; y < 80; y++) for (int x = 0; x < 80; x++)
            assertEquals(expected.getRGB(x, y), actual.getRGB(x, y), "pixel at " + x + "," + y);
    }

    @Test
    void expiredAndForeignGrantsAreRejectedBeforeImageLookup() {
        var ref = issue(DailyGameMediaSlot.CLUE, DailyGameImageVariant.BLUR_9, "/clue.png");
        assertThrows(NotFoundException.class, () -> service.fetch(UUID.randomUUID(), token(ref)));
        clock.advanceSeconds(601);
        assertThrows(NotFoundException.class, () -> service.fetch(user, token(ref)));
        verifyNoInteractions(tmdb);
    }

    @ParameterizedTest
    @EnumSource(value = DailyGameMediaSlot.class, names = {"CLUE", "FRAME_2", "SERIES_POSTER"})
    void changedChallengeSourceRejectsGrantBeforeImageLookup(DailyGameMediaSlot slot) {
        var ref = issue(slot, DailyGameImageVariant.ORIGINAL, "/original.png");
        DailyChallenge previous = currentChallenge.get();
        currentChallenge.set(switch (slot) {
            case CLUE -> previous.toBuilder().imagePath("/replacement.png").build();
            case FRAME_2 -> previous.toBuilder().displaySnapshot(Map.of("imagePaths", List.of(
                    "/other-0.png", "/other-1.png", "/replacement.png",
                    "/other-3.png", "/other-4.png", "/other-5.png"))).build();
            case SERIES_POSTER -> previous.toBuilder().answerSnapshot(Map.of(
                    "presentation", Map.of("seriesPosterPath", "/replacement.png"))).build();
            default -> throw new IllegalStateException();
        });
        assertThrows(NotFoundException.class, () -> service.fetch(user, token(ref)));
        verifyNoInteractions(tmdb);
    }

    @Test
    void changedChallengeSourceRejectsPreviouslyFetchedGrantDespiteCachedBytes() throws Exception {
        byte[] source = image(80, 80, "png");
        when(tmdb.getImageBytes("/original.png")).thenReturn(new TmdbLookupResult.Found<>(
                new TmdbImageBytes(source, MediaType.IMAGE_PNG)));
        var ref = issue(DailyGameMediaSlot.CLUE, DailyGameImageVariant.ORIGINAL, "/original.png");
        assertNotNull(service.fetch(user, token(ref)));

        currentChallenge.set(currentChallenge.get().toBuilder().imagePath("/replacement.png").build());

        assertThrows(NotFoundException.class, () -> service.fetch(user, token(ref)));
        verify(tmdb, times(1)).getImageBytes("/original.png");
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
        var ref = issue(DailyGameMediaSlot.CLUE, DailyGameImageVariant.BLUR_26, "poster.jpg");
        assertTrue(ref.url().startsWith("/games/play/media/"));
    }

    @Test
    void invalidMediaOversizedBytesAndCorruptImageAreRejected() throws Exception {
        for (TmdbImageBytes result : new TmdbImageBytes[] {
                new TmdbImageBytes(image(20, 20, "png"), MediaType.IMAGE_GIF),
                new TmdbImageBytes(new byte[5 * 1024 * 1024 + 1], MediaType.IMAGE_PNG),
                new TmdbImageBytes(new byte[] {1, 2, 3}, MediaType.IMAGE_PNG)}) {
            when(tmdb.getImageBytes("/bad.png")).thenReturn(new TmdbLookupResult.Found<>(result));
            var ref = issue(DailyGameMediaSlot.CLUE, DailyGameImageVariant.BLUR_20, "/bad.png");
            assertThrows(BadRequestException.class, () -> service.fetch(user, token(ref)));
        }
    }

    @Test
    void oversizedPixelsAreRejected() throws Exception {
        byte[] source = image(4200, 2, "png");
        when(tmdb.getImageBytes("/wide.png")).thenReturn(new TmdbLookupResult.Found<>(new TmdbImageBytes(source, MediaType.IMAGE_PNG)));
        var ref = issue(DailyGameMediaSlot.CLUE, DailyGameImageVariant.BLUR_20, "/wide.png");
        assertThrows(BadRequestException.class, () -> service.fetch(user, token(ref)));
    }

    @Test
    void rejectsImageWhoseBytesDisagreeWithDeclaredMediaType() throws Exception {
        byte[] jpeg = image(80, 80, "jpeg");
        when(tmdb.getImageBytes("/wrong.png")).thenReturn(new TmdbLookupResult.Found<>(
                new TmdbImageBytes(jpeg, MediaType.IMAGE_PNG)));
        var ref = issue(DailyGameMediaSlot.FRAME_0, DailyGameImageVariant.ORIGINAL, "/wrong.png");
        assertThrows(BadRequestException.class, () -> service.fetch(user, token(ref)));
    }

    @Test
    void authorizedGrantsShareTransformedBytesButForeignUserCannotReadCache() throws Exception {
        byte[] source = image(80, 80, "png");
        when(tmdb.getImageBytes("/same.png")).thenReturn(new TmdbLookupResult.Found<>(new TmdbImageBytes(source, MediaType.IMAGE_PNG)));
        var first = issue(DailyGameMediaSlot.CLUE, DailyGameImageVariant.BLUR_5, "/same.png");
        var second = issue(DailyGameMediaSlot.CLUE, DailyGameImageVariant.BLUR_5, "/same.png");
        byte[] one = service.fetch(user, token(first)).body();
        assertThrows(NotFoundException.class, () -> service.fetch(UUID.randomUUID(), token(second)));
        assertArrayEquals(one, service.fetch(user, token(second)).body());
        verify(tmdb, times(1)).getImageBytes("/same.png");
    }

    private static String token(DailyGameMediaRefDTO ref) {
        return ref.url().substring(ref.url().lastIndexOf('/') + 1);
    }

    private DailyGameMediaRefDTO issue(DailyGameMediaSlot slot, DailyGameImageVariant variant, String path) {
        List<String> frames = new java.util.ArrayList<>(List.of(
                "/other-0.png", "/other-1.png", "/other-2.png",
                "/other-3.png", "/other-4.png", "/other-5.png"));
        if (slot.name().startsWith("FRAME_")) {
            frames.set(slot.ordinal() - DailyGameMediaSlot.FRAME_0.ordinal(), path);
        }
        currentChallenge.set(DailyChallenge.builder().id(challenge).imagePath(path)
                .displaySnapshot(Map.of("imagePaths", frames))
                .answerSnapshot(Map.of("presentation", Map.of("seriesPosterPath", path)))
                .build());
        return service.issue(user, challenge, slot, variant, path);
    }

    private static byte[] image(int width, int height, String format) throws Exception {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < height; y++) for (int x = 0; x < width; x++)
            image.setRGB(x, y, ((x / 8 + y / 8) & 1) == 0 ? Color.RED.getRGB() : Color.BLUE.getRGB());
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(image, format, out);
        return out.toByteArray();
    }

    private static byte[] metadataBearingPng(byte[] png) throws Exception {
        byte[] text = "Comment\0private-fixture-metadata".getBytes(StandardCharsets.ISO_8859_1);
        byte[] type = "tEXt".getBytes(StandardCharsets.US_ASCII);
        ByteArrayOutputStream chunk = new ByteArrayOutputStream();
        chunk.write(new byte[] {0, 0, 0, (byte) text.length});
        chunk.write(type);
        chunk.write(text);
        CRC32 crc = new CRC32();
        crc.update(type);
        crc.update(text);
        int checksum = (int) crc.getValue();
        chunk.write(new byte[] {(byte) (checksum >>> 24), (byte) (checksum >>> 16),
                (byte) (checksum >>> 8), (byte) checksum});
        byte[] metadata = chunk.toByteArray();
        int firstChunkEnd = 8 + 4 + 4 + 13 + 4;
        ByteArrayOutputStream fixture = new ByteArrayOutputStream();
        fixture.write(png, 0, firstChunkEnd);
        fixture.write(metadata);
        fixture.write(png, firstChunkEnd, png.length - firstChunkEnd);
        return fixture.toByteArray();
    }

    private static final class MutableClock extends Clock {
        private Instant now = Instant.parse("2026-10-14T00:00:00Z");
        @Override public ZoneId getZone() { return ZoneId.of("UTC"); }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }
        void advanceSeconds(long seconds) { now = now.plusSeconds(seconds); }
    }
}
