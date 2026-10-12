package com.watchwise.watchwise_api.pick.dto;

import java.util.UUID;

public record PickOptionViewDTO(
        UUID id,
        PickOptionSearchDTO target,
        PickTargetPreviewDTO preview) {}
