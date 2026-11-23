package com.watchwise.watchwise_api.seriesprogress.controller;

import com.watchwise.watchwise_api.seriesprogress.dto.IndividualSeriesProgressDTO;
import com.watchwise.watchwise_api.seriesprogress.service.SeriesProgressReader;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequiredArgsConstructor
public class IndividualSeriesProgressController {

    private final SeriesProgressReader seriesProgressReader;

    @GetMapping("/users/me/series-progress/{seriesTmdbId}")
    public ResponseEntity<IndividualSeriesProgressDTO> getMySeriesProgress(
            @AuthenticationPrincipal UUID viewerId,
            @PathVariable String seriesTmdbId) {
        return seriesProgressReader.readStartedSeries(viewerId, seriesTmdbId)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.noContent().build());
    }
}
