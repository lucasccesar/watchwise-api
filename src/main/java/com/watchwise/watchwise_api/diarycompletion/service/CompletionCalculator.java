package com.watchwise.watchwise_api.diarycompletion.service;

import com.watchwise.watchwise_api.diarycompletion.model.CompletedPass;
import com.watchwise.watchwise_api.diarycompletion.model.CompletionScope;
import com.watchwise.watchwise_api.diarycompletion.model.EpisodeCoordinate;
import com.watchwise.watchwise_api.diarycompletion.model.EpisodeWatch;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Collectors;

public class CompletionCalculator {

    private static final Comparator<EpisodeWatch> ACTIVITY_ORDER = Comparator
            .comparing(CompletionCalculator::knownWatchTime, Comparator.nullsLast(Comparator.naturalOrder()))
            .thenComparing(EpisodeWatch::recordedAt, Comparator.nullsFirst(Comparator.naturalOrder()))
            .thenComparing(EpisodeWatch::diaryEntryId);

    public List<CompletedPass> calculate(CompletionScope scope, List<EpisodeWatch> watches) {
        Objects.requireNonNull(scope, "scope is required");
        if (!scope.releaseComplete() || scope.episodes().isEmpty() || watches == null || watches.isEmpty()) {
            return List.of();
        }

        Map<Integer, List<EpisodeWatch>> watchesByPass = watches.stream()
                .filter(Objects::nonNull)
                .collect(Collectors.groupingBy(EpisodeWatch::watchNumber, TreeMap::new, Collectors.toList()));
        List<CompletedPass> completedPasses = new ArrayList<>();

        for (Map.Entry<Integer, List<EpisodeWatch>> pass : watchesByPass.entrySet()) {
            Set<EpisodeCoordinate> presentEpisodes = pass.getValue().stream()
                    .map(EpisodeWatch::episode)
                    .collect(Collectors.toCollection(HashSet::new));
            if (!presentEpisodes.containsAll(scope.episodes())) {
                continue;
            }

            Map<EpisodeCoordinate, EpisodeWatch> firstNecessaryWatch = new HashMap<>();
            for (EpisodeWatch watch : pass.getValue()) {
                if (!scope.episodes().contains(watch.episode())) {
                    continue;
                }
                firstNecessaryWatch.merge(watch.episode(), watch,
                        (first, candidate) -> ACTIVITY_ORDER.compare(first, candidate) <= 0 ? first : candidate);
            }

            EpisodeWatch completionEvent = firstNecessaryWatch.values().stream()
                    .max(ACTIVITY_ORDER)
                    .orElseThrow();
            completedPasses.add(new CompletedPass(
                    pass.getKey(),
                    completionEvent.diaryEntryId(),
                    completedOn(completionEvent),
                    orderedAt(completionEvent)));
        }

        return List.copyOf(completedPasses);
    }

    private static Instant knownWatchTime(EpisodeWatch watch) {
        if (watch.watchedAt() != null) {
            return watch.watchedAt();
        }
        return watch.watchedDate() == null
                ? null
                : watch.watchedDate().atStartOfDay(ZoneOffset.UTC).toInstant();
    }

    private static LocalDate completedOn(EpisodeWatch watch) {
        if (watch.watchedAt() != null) {
            return LocalDate.ofInstant(watch.watchedAt(), ZoneOffset.UTC);
        }
        return watch.watchedDate();
    }

    private static Instant orderedAt(EpisodeWatch watch) {
        Instant knownWatchTime = knownWatchTime(watch);
        return knownWatchTime == null ? watch.recordedAt() : knownWatchTime;
    }
}
