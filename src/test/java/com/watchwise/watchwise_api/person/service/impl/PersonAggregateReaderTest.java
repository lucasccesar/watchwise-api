package com.watchwise.watchwise_api.person.service.impl;

import com.watchwise.watchwise_api.common.exception.BadRequestException;
import com.watchwise.watchwise_api.common.exception.NotFoundException;
import com.watchwise.watchwise_api.common.exception.TmdbUnavailableException;
import com.watchwise.watchwise_api.common.tmdb.TmdbClient;
import com.watchwise.watchwise_api.common.tmdb.TmdbLookupResult;
import com.watchwise.watchwise_api.common.tmdb.TmdbPersonAggregate;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class PersonAggregateReaderTest {
    private final TmdbClient tmdbClient = mock(TmdbClient.class);
    private final PersonAggregateReader reader = new PersonAggregateReader(tmdbClient);

    @Test
    void readsFoundAggregateUsingGivenLanguage() {
        TmdbPersonAggregate aggregate = mock(TmdbPersonAggregate.class);
        when(tmdbClient.getPersonAggregate("287", "en-US")).thenReturn(new TmdbLookupResult.Found<>(aggregate));

        assertThat(reader.read("287", "en-US")).isSameAs(aggregate);
        verify(tmdbClient).getPersonAggregate("287", "en-US");
    }

    @Test
    void rejectsInvalidAndOverlongIdsBeforeCallingTmdb() {
        assertThatThrownBy(() -> reader.read("invalid", "en-US")).isInstanceOf(BadRequestException.class);
        assertThatThrownBy(() -> reader.read("123456789012345678901", "en-US")).isInstanceOf(BadRequestException.class);
        verifyNoInteractions(tmdbClient);
    }

    @Test
    void mapsNotFoundToNotFoundException() {
        when(tmdbClient.getPersonAggregate("287", "en-US")).thenReturn(new TmdbLookupResult.NotFound<>());
        assertThatThrownBy(() -> reader.read("287", "en-US")).isInstanceOf(NotFoundException.class);
    }

    @Test
    void mapsUnavailableAndNullFoundToUnavailableException() {
        when(tmdbClient.getPersonAggregate("287", "en-US")).thenReturn(new TmdbLookupResult.Unavailable<>());
        assertThatThrownBy(() -> reader.read("287", "en-US")).isInstanceOf(TmdbUnavailableException.class);
        when(tmdbClient.getPersonAggregate("288", "en-US")).thenReturn(new TmdbLookupResult.Found<>(null));
        assertThatThrownBy(() -> reader.read("288", "en-US")).isInstanceOf(TmdbUnavailableException.class);
    }
}
