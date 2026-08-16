package com.watchwise.watchwise_api.diaryentry.dto;

import java.util.List;

public record DeletionImpactDTO(
        List<DeletionImpactItemDTO> wouldDelete
) {
}
