package com.watchwise.watchwise_api.content.service.impl;

import com.watchwise.watchwise_api.common.concurrent.ShutdownAwareRunnable;
import com.watchwise.watchwise_api.common.tmdb.TmdbCardMetadata;
import com.watchwise.watchwise_api.common.tmdb.TmdbClient;
import com.watchwise.watchwise_api.content.service.ContentCoordinate;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;
import java.util.stream.Collectors;

@Component
public class TmdbVoteAverageBatchResolver {

    private final TmdbCardMetadataResolver metadataResolver;
    private final Executor metadataResolutionExecutor;

    public TmdbVoteAverageBatchResolver(
            TmdbCardMetadataResolver metadataResolver,
            @Qualifier("contentCardMetadataResolutionExecutor") Executor metadataResolutionExecutor) {
        this.metadataResolver = metadataResolver;
        this.metadataResolutionExecutor = metadataResolutionExecutor;
    }

    public Map<ContentCoordinate, Double> resolve(Collection<ContentCoordinate> coordinates, String language) {
        if (coordinates == null || coordinates.isEmpty()) {
            return Map.of();
        }

        String resolvedLanguage = language == null || language.isBlank()
                ? TmdbClient.LANGUAGE_INDEPENDENT_LOOKUP_LANGUAGE
                : language;
        List<ContentCoordinate> distinctCoordinates = coordinates.stream()
                .filter(java.util.Objects::nonNull)
                .distinct()
                .toList();
        Map<ContentCoordinate, CompletableFuture<Double>> futures = distinctCoordinates.stream()
                .collect(Collectors.toMap(
                        Function.identity(),
                        coordinate -> resolveAsync(coordinate, resolvedLanguage),
                        (first, ignored) -> first,
                        LinkedHashMap::new));

        Map<ContentCoordinate, Double> results = new LinkedHashMap<>();
        for (Map.Entry<ContentCoordinate, CompletableFuture<Double>> entry : futures.entrySet()) {
            try {
                results.put(entry.getKey(), entry.getValue().join());
            } catch (CompletionException exception) {
                Throwable cause = exception.getCause();
                while (cause instanceof CompletionException completionException
                        && completionException.getCause() != null) {
                    cause = completionException.getCause();
                }
                if (cause instanceof Error error) {
                    throw error;
                }
                results.put(entry.getKey(), null);
            }
        }
        return Collections.unmodifiableMap(results);
    }

    private CompletableFuture<Double> resolveAsync(ContentCoordinate coordinate, String language) {
        CompletableFuture<Double> future = new CompletableFuture<>();
        AtomicBoolean started = new AtomicBoolean();
        ShutdownAwareRunnable task = new ShutdownAwareRunnable() {
            @Override
            public void run() {
                started.set(true);
                try {
                    future.complete(resolveOne(coordinate, language));
                } catch (Error error) {
                    future.completeExceptionally(error);
                    throw error;
                }
            }

            @Override
            public boolean hasStarted() {
                return started.get();
            }

            @Override
            public void onShutdownRejection() {
                future.complete(null);
            }
        };
        try {
            metadataResolutionExecutor.execute(task);
        } catch (RuntimeException rejected) {
            future.complete(null);
        }
        return future;
    }

    private Double resolveOne(ContentCoordinate coordinate, String language) {
        try {
            return metadataResolver.resolve(coordinate, language)
                    .toOptional()
                    .map(TmdbCardMetadata::voteAverage)
                    .orElse(null);
        } catch (RuntimeException ignored) {
            return null;
        }
    }
}
