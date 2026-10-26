package com.watchwise.watchwise_api.pickstemplate.service.impl;

import com.watchwise.watchwise_api.pick.dto.PickContentTargetDTO;
import com.watchwise.watchwise_api.pick.dto.PickTargetDTO;
import com.watchwise.watchwise_api.pickstemplate.dto.PicksTemplateCategoryCreationDTO;
import com.watchwise.watchwise_api.pickstemplate.dto.PicksTemplateCreationDTO;
import com.watchwise.watchwise_api.pickstemplate.dto.PicksTemplateOptionCreationDTO;
import com.watchwise.watchwise_api.pickstemplate.service.PreparedTemplateOption;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;

final class PreparedTemplateCreationDigest {
    private PreparedTemplateCreationDigest() {
    }

    static String compute(UUID actorId, PicksTemplateCreationDTO dto, List<PreparedTemplateOption> options,
                          LocalDateTime validatedAt) {
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
        add(digest, "prepared-picks-template-v2");
        add(digest, actorId);
        add(digest, validatedAt);
        add(digest, dto.name());
        add(digest, dto.description());
        add(digest, dto.coverImage());
        add(digest, dto.instructions());
        add(digest, dto.eligibilityStartDate());
        add(digest, dto.eligibilityEndDate());
        add(digest, dto.categories().size());
        for (PicksTemplateCategoryCreationDTO category : dto.categories()) {
            add(digest, category.name());
            add(digest, category.description());
            add(digest, category.group());
            add(digest, category.displayOrder());
            add(digest, category.allowedType());
            add(digest, category.optionMode());
            add(digest, category.options() == null ? null : category.options().size());
            if (category.options() != null) {
                for (PicksTemplateOptionCreationDTO option : category.options()) {
                    addTarget(digest, option == null ? null : option.target());
                }
            }
        }
        List<PreparedTemplateOption> ordered = options.stream()
                .sorted(Comparator.comparingInt(PreparedTemplateOption::categoryIndex)
                        .thenComparingInt(PreparedTemplateOption::optionIndex))
                .toList();
        add(digest, ordered.size());
        for (PreparedTemplateOption option : ordered) {
            add(digest, option.categoryIndex());
            add(digest, option.optionIndex());
            add(digest, option.contentId());
            add(digest, option.personTmdbId());
            add(digest, option.contextContentId());
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private static void addTarget(MessageDigest digest, PickTargetDTO target) {
        if (target == null) {
            add(digest, (String) null);
            return;
        }
        add(digest, "target");
        addContent(digest, target.content());
        add(digest, target.personTmdbId());
        addContent(digest, target.contextContent());
    }

    private static void addContent(MessageDigest digest, PickContentTargetDTO target) {
        if (target == null) {
            add(digest, (String) null);
            return;
        }
        add(digest, "content");
        add(digest, target.type());
        add(digest, target.tmdbId());
        add(digest, target.seriesTmdbId());
        add(digest, target.seasonNumber());
        add(digest, target.episodeNumber());
    }

    private static void add(MessageDigest digest, Object value) {
        if (value == null) {
            digest.update(ByteBuffer.allocate(Integer.BYTES).putInt(-1).array());
            return;
        }
        byte[] bytes = value.toString().getBytes(StandardCharsets.UTF_8);
        digest.update(ByteBuffer.allocate(Integer.BYTES).putInt(bytes.length).array());
        digest.update(bytes);
    }
}
