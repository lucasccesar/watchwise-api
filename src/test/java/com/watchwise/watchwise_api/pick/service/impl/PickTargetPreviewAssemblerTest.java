package com.watchwise.watchwise_api.pick.service.impl;

import com.watchwise.watchwise_api.common.tmdb.TmdbClient;
import com.watchwise.watchwise_api.common.tmdb.TmdbLookupResult;
import com.watchwise.watchwise_api.common.tmdb.TmdbPersonDetails;
import com.watchwise.watchwise_api.content.dto.ContentCardDTO;
import com.watchwise.watchwise_api.content.dto.ContentCardFieldSet;
import com.watchwise.watchwise_api.content.dto.ContentPreviewStatus;
import com.watchwise.watchwise_api.content.dto.ContentRefDTO;
import com.watchwise.watchwise_api.content.entity.ContentType;
import com.watchwise.watchwise_api.content.service.ContentCardContext;
import com.watchwise.watchwise_api.content.service.ContentCardSpec;
import com.watchwise.watchwise_api.content.service.ContentCoordinate;
import com.watchwise.watchwise_api.content.service.impl.ContentCardAssembler;
import com.watchwise.watchwise_api.pick.dto.PickOptionSearchDTO;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;
import org.mockito.ArgumentCaptor;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PickTargetPreviewAssemblerTest {
    private final ContentCardAssembler contentCards = mock(ContentCardAssembler.class);
    private final TmdbClient tmdbClient = mock(TmdbClient.class);
    private final Executor directExecutor = Runnable::run;
    private final PickTargetPreviewAssembler assembler = new PickTargetPreviewAssembler(contentCards, tmdbClient, directExecutor);
    private final ContentCardContext context = new ContentCardContext("en-US", "US", null, null);

    @Test
    @DisplayName("[assemble] Should Resolve A Repeated Content Coordinate Once And Preserve Order - When Targets Repeat")
    void shouldResolveRepeatedContentCoordinateOnceAndPreserveTargetOrderWhenCoordinatesRepeat() {
        PickOptionSearchDTO target = contentTarget("550", ContentType.MOVIE);
        ContentCardDTO card = card(ContentType.MOVIE, "550", ContentPreviewStatus.PARTIAL);
        when(contentCards.assemble(anyCollection(), eq(context), eq(ContentCardFieldSet.BASIC_METADATA)))
                .thenReturn(Map.of(new ContentCoordinate(ContentType.MOVIE, "550", null, null, null), card));

        var previews = assembler.assemble(List.of(target, target), context);

        assertThat(previews).hasSize(2);
        assertThat(previews).extracting(preview -> preview.contentCard().previewStatus())
                .containsExactly(ContentPreviewStatus.PARTIAL, ContentPreviewStatus.PARTIAL);
        ArgumentCaptor<Collection<ContentCardSpec>> specs = ArgumentCaptor.forClass(Collection.class);
        verify(contentCards).assemble(specs.capture(), eq(context), eq(ContentCardFieldSet.BASIC_METADATA));
        assertThat(specs.getValue()).hasSize(1);
    }

    @Test
    @DisplayName("[assemble] Should Preserve An Unavailable Content Card - When TMDB Cannot Resolve Metadata")
    void shouldPassThroughUnavailableContentCardMetadataWhenTmdbDoesNotResolve() {
        PickOptionSearchDTO target = contentTarget("999", ContentType.SERIES);
        ContentCardDTO card = card(ContentType.SERIES, "999", ContentPreviewStatus.UNAVAILABLE);
        when(contentCards.assemble(anyCollection(), eq(context), eq(ContentCardFieldSet.BASIC_METADATA)))
                .thenReturn(Map.of(new ContentCoordinate(ContentType.SERIES, "999", null, null, null), card));

        var previews = assembler.assemble(List.of(target), context);

        assertThat(previews.get(0).contentCard().previewStatus()).isEqualTo(ContentPreviewStatus.UNAVAILABLE);
    }

    @Test
    @DisplayName("[assemble] Should Resolve Repeated Person And Context Metadata Once - When Targets Repeat")
    void shouldResolveRepeatedPersonAndContextCoordinatesOnceWhenTargetsRepeat() {
        PickOptionSearchDTO target = personTarget("42", contentRef("550", ContentType.MOVIE));
        ContentCardDTO contextCard = card(ContentType.MOVIE, "550", ContentPreviewStatus.UNAVAILABLE);
        when(contentCards.assemble(anyCollection(), eq(context), eq(ContentCardFieldSet.BASIC_METADATA)))
                .thenReturn(Map.of(new ContentCoordinate(ContentType.MOVIE, "550", null, null, null), contextCard));
        when(tmdbClient.getPersonDetails("42")).thenReturn(found(new TmdbPersonDetails("42", "Ada", "/ada.jpg", null)));

        var previews = assembler.assemble(List.of(target, target), context);

        assertThat(previews).hasSize(2);
        assertThat(previews.get(0).person().tmdbId()).isEqualTo("42");
        assertThat(previews.get(0).person().name()).isEqualTo("Ada");
        assertThat(previews.get(0).person().profileUrl()).endsWith("/w185/ada.jpg");
        assertThat(previews.get(0).contextCard().previewStatus()).isEqualTo(ContentPreviewStatus.UNAVAILABLE);
        verify(tmdbClient, times(1)).getPersonDetails("42");
        ArgumentCaptor<Collection<ContentCardSpec>> specs = ArgumentCaptor.forClass(Collection.class);
        verify(contentCards).assemble(specs.capture(), eq(context), eq(ContentCardFieldSet.BASIC_METADATA));
        assertThat(specs.getValue()).hasSize(1);
    }

    @Test
    @DisplayName("[assemble] Should Keep Content And Person Results In Input Order - When Target Types Are Mixed")
    void shouldKeepInputOrderWhenContentAndPersonTargetsAreMixed() {
        PickOptionSearchDTO movie = contentTarget("550", ContentType.MOVIE);
        PickOptionSearchDTO person = personTarget("42", null);
        PickOptionSearchDTO series = contentTarget("1396", ContentType.SERIES);
        ContentCardDTO movieCard = card(ContentType.MOVIE, "550", ContentPreviewStatus.AVAILABLE);
        ContentCardDTO seriesCard = card(ContentType.SERIES, "1396", ContentPreviewStatus.PARTIAL);
        when(contentCards.assemble(anyCollection(), eq(context), eq(ContentCardFieldSet.BASIC_METADATA)))
                .thenReturn(Map.of(
                        new ContentCoordinate(ContentType.MOVIE, "550", null, null, null), movieCard,
                        new ContentCoordinate(ContentType.SERIES, "1396", null, null, null), seriesCard));
        when(tmdbClient.getPersonDetails("42"))
                .thenReturn(found(new TmdbPersonDetails("42", "Ada", "/ada.jpg", null)));

        var previews = assembler.assemble(List.of(movie, person, series), context);

        assertThat(previews).hasSize(3);
        assertThat(previews.get(0).contentCard().tmdbId()).isEqualTo("550");
        assertThat(previews.get(1).person().tmdbId()).isEqualTo("42");
        assertThat(previews.get(2).contentCard().tmdbId()).isEqualTo("1396");
    }

    @Test
    @DisplayName("[assemble] Should Retain Partial Person Metadata - When TMDB Omits A Profile Image")
    void shouldKeepPersonWithPartialDetailsWhenProfileIsMissing() {
        when(tmdbClient.getPersonDetails("42"))
                .thenReturn(found(new TmdbPersonDetails("42", "Ada", null, null)));

        var previews = assembler.assemble(List.of(personTarget("42", null)), context);

        assertThat(previews.get(0).person().tmdbId()).isEqualTo("42");
        assertThat(previews.get(0).person().name()).isEqualTo("Ada");
        assertThat(previews.get(0).person().previewStatus()).isEqualTo(ContentPreviewStatus.PARTIAL);
    }

    @Test
    @DisplayName("[assemble] Should Retain The Person ID - When TMDB Details Are Unavailable")
    void shouldKeepPersonTargetWhenDetailsAreUnavailable() {
        when(tmdbClient.getPersonDetails("77")).thenReturn(new TmdbLookupResult.Unavailable<>());

        var previews = assembler.assemble(List.of(personTarget("77", null)), context);

        assertThat(previews).singleElement().satisfies(preview -> {
            assertThat(preview.person().tmdbId()).isEqualTo("77");
            assertThat(preview.person().previewStatus()).isEqualTo(ContentPreviewStatus.UNAVAILABLE);
        });
    }

    private static PickOptionSearchDTO contentTarget(String id, ContentType type) {
        return new PickOptionSearchDTO(null, contentRef(id, type), null, null);
    }

    private static PickOptionSearchDTO personTarget(String id, ContentRefDTO context) {
        return new PickOptionSearchDTO(null, null, id, context);
    }

    private static ContentRefDTO contentRef(String id, ContentType type) {
        return new ContentRefDTO(null, id, type, null, null, null, null, null, null, null);
    }

    private static ContentCardDTO card(ContentType type, String id, ContentPreviewStatus status) {
        return new ContentCardDTO(null, type, id, null, null, null,
                null, null, null, null, null, null, null, null, null,
                null, null, null, status);
    }

    private static TmdbLookupResult<TmdbPersonDetails> found(TmdbPersonDetails person) {
        return new TmdbLookupResult.Found<>(person);
    }
}
