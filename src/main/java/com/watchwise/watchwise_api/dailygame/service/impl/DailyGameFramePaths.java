package com.watchwise.watchwise_api.dailygame.service.impl;

import com.watchwise.watchwise_api.common.exception.BadRequestException;
import com.watchwise.watchwise_api.common.tmdb.TmdbClient;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

final class DailyGameFramePaths {
    private DailyGameFramePaths() {}

    static List<String> select(Map<String, Object> snapshot) {
        if (snapshot == null || !(snapshot.get("imagePaths") instanceof List<?> paths)) {
            return List.of();
        }
        LinkedHashSet<String> selected = new LinkedHashSet<>();
        for (Object path : paths) {
            if (!(path instanceof String text)) {
                continue;
            }
            try {
                selected.add(TmdbClient.normalizeImagePath(text));
            } catch (BadRequestException invalidPath) {
                continue;
            }
            if (selected.size() == 6) {
                break;
            }
        }
        return List.copyOf(selected);
    }
}
