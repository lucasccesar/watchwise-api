package com.watchwise.watchwise_api.common.tmdb;

import java.util.Optional;

public sealed interface TmdbLookupResult<T> {

    record Found<T>(T value) implements TmdbLookupResult<T> {
    }

    record NotFound<T>() implements TmdbLookupResult<T> {
    }

    record Unavailable<T>() implements TmdbLookupResult<T> {
    }

    default Optional<T> toOptional() {
        return switch (this) {
            case Found<T> found -> Optional.of(found.value());
            case NotFound<T> ignored -> Optional.empty();
            case Unavailable<T> ignored -> Optional.empty();
        };
    }

    default boolean isNotFound() {
        return this instanceof NotFound<T>;
    }

    default boolean isUnavailable() {
        return this instanceof Unavailable<T>;
    }
}
