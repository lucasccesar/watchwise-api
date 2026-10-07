package com.watchwise.watchwise_api.content.service;

import com.watchwise.watchwise_api.content.dto.ContentViewerStateDTO;
import com.watchwise.watchwise_api.content.entity.Content;

import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

public interface ContentViewerStateService {
    Resolution resolve(
            UUID viewerId,
            Collection<ContentCoordinate> coordinates,
            Map<ContentCoordinate, ContentSchedule> schedulesByCoordinate);

    record Resolution(
            Map<ContentCoordinate, ContentViewerStateDTO> statesByCoordinate,
            Map<ContentCoordinate, UUID> existingContentIdsByCoordinate) {

        public Resolution {
            statesByCoordinate = immutableCopy(statesByCoordinate);
            existingContentIdsByCoordinate = immutableCopy(existingContentIdsByCoordinate);
        }

        private static <K, V> Map<K, V> immutableCopy(Map<K, V> values) {
            if (values == null || values.isEmpty()) {
                return Map.of();
            }
            return Collections.unmodifiableMap(new LinkedHashMap<>(values));
        }
    }
}
