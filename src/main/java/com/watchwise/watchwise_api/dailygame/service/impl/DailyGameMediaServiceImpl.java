package com.watchwise.watchwise_api.dailygame.service.impl;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.watchwise.watchwise_api.common.exception.BadRequestException;
import com.watchwise.watchwise_api.common.exception.NotFoundException;
import com.watchwise.watchwise_api.common.exception.TmdbUnavailableException;
import com.watchwise.watchwise_api.common.tmdb.TmdbClient;
import com.watchwise.watchwise_api.common.tmdb.TmdbImageBytes;
import com.watchwise.watchwise_api.common.tmdb.TmdbImageTransport;
import com.watchwise.watchwise_api.common.tmdb.TmdbLookupResult;
import com.watchwise.watchwise_api.dailygame.dto.DailyGameMediaRefDTO;
import com.watchwise.watchwise_api.dailygame.dto.DailyGameMediaResponseDTO;
import com.watchwise.watchwise_api.dailygame.entity.DailyChallenge;
import com.watchwise.watchwise_api.dailygame.media.DailyGameImageVariant;
import com.watchwise.watchwise_api.dailygame.media.DailyGameMediaSlot;
import com.watchwise.watchwise_api.dailygame.repository.DailyChallengeRepository;
import com.watchwise.watchwise_api.dailygame.service.DailyGameMediaService;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;

import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import java.awt.AlphaComposite;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class DailyGameMediaServiceImpl implements DailyGameMediaService {
    private static final Duration GRANT_LIFETIME = Duration.ofMinutes(10);
    private static final int MAX_DIMENSION = 4096;
    private static final long MAX_PIXELS = 16_000_000;
    private final TmdbClient tmdbClient;
    private final DailyChallengeRepository challenges;
    private final Clock clock;
    private final SecureRandom random = new SecureRandom();
    private final Cache<String, Grant> grants = Caffeine.newBuilder()
            .maximumSize(10_000).expireAfterWrite(GRANT_LIFETIME).build();
    private final Cache<MediaKey, DailyGameMediaResponseDTO> transformed = Caffeine.newBuilder()
            .maximumWeight(50L * 1024 * 1024)
            .weigher((MediaKey key, DailyGameMediaResponseDTO value) -> value.body().length)
            .expireAfterWrite(Duration.ofMinutes(10)).build();

    public DailyGameMediaServiceImpl(TmdbClient tmdbClient, DailyChallengeRepository challenges, Clock clock) {
        this.tmdbClient = tmdbClient;
        this.challenges = challenges;
        this.clock = clock;
    }

    @Override
    public DailyGameMediaRefDTO issue(UUID userId, UUID challengeId, DailyGameMediaSlot slot,
                                       DailyGameImageVariant variant, String storedImagePath) {
        if (userId == null || challengeId == null || slot == null || variant == null) {
            throw new BadRequestException("Incomplete daily game media grant");
        }
        String path = TmdbClient.normalizeImagePath(storedImagePath);
        byte[] tokenBytes = new byte[32];
        random.nextBytes(tokenBytes);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(tokenBytes);
        grants.put(token, new Grant(userId, challengeId, slot, variant, path,
                fingerprint(path), clock.instant().plus(GRANT_LIFETIME)));
        return new DailyGameMediaRefDTO(slot, "/games/play/media/" + token, variant,
                variant == DailyGameImageVariant.ORIGINAL);
    }

    @Override
    public DailyGameMediaResponseDTO fetch(UUID userId, String grantToken) {
        Grant grant = grantToken == null ? null : grants.getIfPresent(grantToken);
        if (grant == null || !grant.userId().equals(userId) || !clock.instant().isBefore(grant.expiresAt())
                || !matchesCurrentSource(grant)) {
            throw new NotFoundException("Daily game media grant not found");
        }
        MediaKey key = new MediaKey(grant.challengeId(), grant.slot(), grant.variant(), grant.sourceFingerprint());
        DailyGameMediaResponseDTO response = transformed.get(key, ignored -> load(grant));
        return new DailyGameMediaResponseDTO(response.body().clone(), response.mediaType());
    }

    private boolean matchesCurrentSource(Grant grant) {
        DailyChallenge challenge = challenges.findById(grant.challengeId()).orElse(null);
        if (challenge == null) {
            return false;
        }
        String currentPath = switch (grant.slot()) {
            case CLUE -> challenge.getImagePath();
            case SERIES_POSTER -> seriesPosterPath(challenge.getAnswerSnapshot());
            case FRAME_0, FRAME_1, FRAME_2, FRAME_3, FRAME_4, FRAME_5 ->
                    framePath(challenge.getDisplaySnapshot(), grant.slot());
        };
        try {
            return fingerprint(TmdbClient.normalizeImagePath(currentPath)).equals(grant.sourceFingerprint());
        } catch (BadRequestException invalidSource) {
            return false;
        }
    }

    private static String framePath(Map<String, Object> snapshot, DailyGameMediaSlot slot) {
        List<String> paths = DailyGameFramePaths.select(snapshot);
        int index = slot.ordinal() - DailyGameMediaSlot.FRAME_0.ordinal();
        return index < paths.size() ? paths.get(index) : null;
    }

    private static String seriesPosterPath(Map<String, Object> snapshot) {
        if (snapshot == null) {
            return null;
        }
        Object presentation = snapshot.get("presentation");
        Object path = presentation instanceof Map<?, ?> map ? map.get("seriesPosterPath") : null;
        if (!(path instanceof String text) || text.isBlank()) {
            path = snapshot.get("seriesPosterPath");
        }
        return path instanceof String text ? text : null;
    }

    private DailyGameMediaResponseDTO load(Grant grant) {
        TmdbLookupResult<TmdbImageBytes> result = tmdbClient.getImageBytes(grant.sourcePath());
        if (result instanceof TmdbLookupResult.Unavailable<TmdbImageBytes>) {
            throw new TmdbUnavailableException("TMDB is temporarily unavailable");
        }
        if (!(result instanceof TmdbLookupResult.Found<TmdbImageBytes> found)) {
            throw new NotFoundException("TMDB image unavailable");
        }
        TmdbImageBytes source = found.value();
        if (source == null || source.body() == null || source.body().length == 0
                || source.body().length > TmdbImageTransport.MAX_BYTES
                || source.mediaType() == null || (!MediaType.IMAGE_JPEG.isCompatibleWith(source.mediaType())
                && !MediaType.IMAGE_PNG.isCompatibleWith(source.mediaType()))) {
            throw new BadRequestException("Unsupported daily game image");
        }
        BufferedImage decoded = decode(source);
        if (grant.variant() == DailyGameImageVariant.ORIGINAL) {
            boolean png = MediaType.IMAGE_PNG.isCompatibleWith(source.mediaType());
            BufferedImage safeImage = new BufferedImage(decoded.getWidth(), decoded.getHeight(),
                    png && decoded.getColorModel().hasAlpha()
                            ? BufferedImage.TYPE_INT_ARGB : BufferedImage.TYPE_INT_RGB);
            Graphics2D graphics = safeImage.createGraphics();
            try {
                graphics.setComposite(AlphaComposite.Src);
                graphics.drawImage(decoded, 0, 0, null);
            } finally {
                graphics.dispose();
            }
            return encode(safeImage, png ? "png" : "jpeg", png ? MediaType.IMAGE_PNG : MediaType.IMAGE_JPEG);
        }
        int downsampleFactor = switch (grant.variant()) {
            case BLUR_26 -> 26;
            case BLUR_20 -> 20;
            case BLUR_14 -> 14;
            case BLUR_9 -> 9;
            case BLUR_5 -> 5;
            case BLUR_2 -> 2;
            case ORIGINAL -> throw new IllegalStateException();
        };
        // Blur strength is the reduction factor: smaller values retain more source detail.
        int width = Math.max(1, decoded.getWidth() / downsampleFactor);
        int height = Math.max(1, decoded.getHeight() / downsampleFactor);
        BufferedImage small = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D down = small.createGraphics();
        try {
            down.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            down.drawImage(decoded, 0, 0, width, height, null);
        } finally {
            down.dispose();
        }
        BufferedImage blurred = new BufferedImage(decoded.getWidth(), decoded.getHeight(), BufferedImage.TYPE_INT_RGB);
        Graphics2D up = blurred.createGraphics();
        try {
            up.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            up.drawImage(small, 0, 0, blurred.getWidth(), blurred.getHeight(), null);
        } finally {
            up.dispose();
        }
        return encode(blurred, "jpeg", MediaType.IMAGE_JPEG);
    }

    private static DailyGameMediaResponseDTO encode(BufferedImage image, String format, MediaType mediaType) {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try {
            if (!ImageIO.write(image, format, output)) {
                throw new BadRequestException("Could not transform daily game image");
            }
        } catch (IOException error) {
            throw new BadRequestException("Could not transform daily game image");
        }
        return new DailyGameMediaResponseDTO(output.toByteArray(), mediaType);
    }

    private static BufferedImage decode(TmdbImageBytes source) {
        try (ImageInputStream input = ImageIO.createImageInputStream(new ByteArrayInputStream(source.body()))) {
            Iterator<ImageReader> readers = ImageIO.getImageReaders(input);
            if (!readers.hasNext()) {
                throw new BadRequestException("Corrupt daily game image");
            }
            ImageReader reader = readers.next();
            try {
                reader.setInput(input, true, true);
                String format = reader.getFormatName();
                if (!format.equalsIgnoreCase("jpeg") && !format.equalsIgnoreCase("jpg")
                        && !format.equalsIgnoreCase("png")) {
                    throw new BadRequestException("Unsupported daily game image");
                }
                boolean decodedPng = format.equalsIgnoreCase("png");
                boolean declaredPng = MediaType.IMAGE_PNG.isCompatibleWith(source.mediaType());
                if (decodedPng != declaredPng) {
                    throw new BadRequestException("Daily game image type does not match its bytes");
                }
                int width = reader.getWidth(0);
                int height = reader.getHeight(0);
                if (width <= 0 || height <= 0 || width > MAX_DIMENSION || height > MAX_DIMENSION
                        || (long) width * height > MAX_PIXELS) {
                    throw new BadRequestException("Daily game image dimensions are too large");
                }
                BufferedImage decoded = reader.read(0);
                if (decoded == null) {
                    throw new BadRequestException("Corrupt daily game image");
                }
                return decoded;
            } finally {
                reader.dispose();
            }
        } catch (IOException | RuntimeException error) {
            if (error instanceof BadRequestException badRequest) {
                throw badRequest;
            }
            throw new BadRequestException("Corrupt daily game image");
        }
    }

    private static String fingerprint(String path) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(path.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private record Grant(UUID userId, UUID challengeId, DailyGameMediaSlot slot, DailyGameImageVariant variant,
                         String sourcePath, String sourceFingerprint, Instant expiresAt) {}
    private record MediaKey(UUID challengeId, DailyGameMediaSlot slot, DailyGameImageVariant variant,
                            String sourceFingerprint) {}
}
