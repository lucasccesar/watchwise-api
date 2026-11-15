package com.watchwise.watchwise_api.diaryentry.service.impl;

import com.watchwise.watchwise_api.common.exception.BadRequestException;
import com.watchwise.watchwise_api.diaryentry.dto.DiaryViewFilter;
import com.watchwise.watchwise_api.diaryentry.repository.DiaryViewRepository.DiaryViewCursorPosition;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.UUID;

final class DiaryViewCursorCodec {

    private static final String VERSION = "v2";

    private DiaryViewCursorCodec() { }

    static DiaryViewCursorPosition decode(String cursor, UUID ownerId, DiaryViewFilter filter) {
        if (cursor == null || cursor.isBlank()) {
            return null;
        }
        try {
            String decoded = new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8);
            String[] values = decoded.split("\\|", -1);
            if (values.length != 10 || !VERSION.equals(values[0])
                    || !ownerId.toString().equals(values[1])
                    || !filterFingerprint(ownerId, filter).equals(values[2])) {
                throw new BadRequestException("Diary view cursor does not match this request");
            }
            int kindRank = Integer.parseInt(values[5]);
            int contentTypeRank = Integer.parseInt(values[6]);
            if ((kindRank != 0 && kindRank != 1) || contentTypeRank < 0 || contentTypeRank > 3) {
                throw new BadRequestException("Invalid diary view cursor");
            }
            return new DiaryViewCursorPosition(Instant.parse(values[3]), UUID.fromString(values[4]),
                    kindRank, contentTypeRank, Instant.parse(values[7]), UUID.fromString(values[8]),
                    values[9].isEmpty() ? null : Integer.valueOf(values[9]));
        } catch (BadRequestException exception) {
            throw exception;
        } catch (IllegalArgumentException | java.time.DateTimeException exception) {
            throw new BadRequestException("Invalid diary view cursor");
        }
    }

    static String encode(UUID ownerId, DiaryViewFilter filter, DiaryViewCursorPosition position) {
        String value = String.join("|", VERSION, ownerId.toString(), filterFingerprint(ownerId, filter),
                position.groupSortAt().toString(), position.groupSortId().toString(),
                Integer.toString(position.kindRank()), Integer.toString(position.contentTypeRank()),
                position.orderedAt().toString(), position.targetId().toString(),
                position.sortScore() == null ? "" : position.sortScore().toString());
        return Base64.getUrlEncoder().withoutPadding().encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }

    private static String filterFingerprint(UUID ownerId, DiaryViewFilter filter) {
        String value = String.join("|", ownerId.toString(),
                filter.type() == null ? "" : filter.type().name(),
                filter.dateStart() == null ? "" : filter.dateStart().toString(),
                filter.dateEnd() == null ? "" : filter.dateEnd().toString(),
                filter.scoreMin() == null ? "" : filter.scoreMin().toString(),
                filter.scoreMax() == null ? "" : filter.scoreMax().toString(),
                filter.hasReview() == null ? "" : filter.hasReview().toString(),
                filter.seriesTmdbId() == null ? "" : filter.seriesTmdbId(),
                filter.watchedInTheater() == null ? "" : filter.watchedInTheater().toString(),
                filter.sortBy().name());
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is not available", exception);
        }
    }
}
