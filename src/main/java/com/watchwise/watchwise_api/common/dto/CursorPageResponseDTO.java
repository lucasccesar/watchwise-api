package com.watchwise.watchwise_api.common.dto;

import java.util.List;

public record CursorPageResponseDTO<T>(
        List<T> content,
        int size,
        String nextCursor,
        boolean hasNext
) {
}
