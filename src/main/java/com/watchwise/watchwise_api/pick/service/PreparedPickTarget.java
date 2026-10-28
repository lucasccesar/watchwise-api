package com.watchwise.watchwise_api.pick.service;

import com.watchwise.watchwise_api.content.service.PreparedContentReference;

import java.util.UUID;

public record PreparedPickTarget(
        ResolvedPickTarget.TargetKey key,
        PreparedContentReference content,
        PreparedContentReference contextContent,
        UUID existingContentId,
        UUID existingContextContentId,
        PreparedTargetValidation validation) {
}
