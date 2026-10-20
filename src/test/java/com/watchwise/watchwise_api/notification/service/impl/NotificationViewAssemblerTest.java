package com.watchwise.watchwise_api.notification.service.impl;

import com.watchwise.watchwise_api.content.dto.ContentCardDTO;
import com.watchwise.watchwise_api.content.dto.ContentCardFieldSet;
import com.watchwise.watchwise_api.content.dto.ContentPreviewStatus;
import com.watchwise.watchwise_api.content.dto.ContentRefDTO;
import com.watchwise.watchwise_api.content.entity.ContentType;
import com.watchwise.watchwise_api.content.service.ContentCardContext;
import com.watchwise.watchwise_api.content.service.ContentCardSpec;
import com.watchwise.watchwise_api.content.service.ContentCoordinate;
import com.watchwise.watchwise_api.content.service.impl.ContentCardAssembler;
import com.watchwise.watchwise_api.notification.dto.NotificationResponseDTO;
import com.watchwise.watchwise_api.notification.dto.NotificationTargetPreviewDTO;
import com.watchwise.watchwise_api.notification.dto.NotificationViewDTO;
import com.watchwise.watchwise_api.notification.entity.NotificationTargetType;
import com.watchwise.watchwise_api.notification.entity.NotificationType;
import com.watchwise.watchwise_api.person.dto.PersonPreviewDTO;
import com.watchwise.watchwise_api.person.service.PersonPreviewResolver;
import com.watchwise.watchwise_api.user.dto.UserPreviewDTO;
import com.watchwise.watchwise_api.user.entity.User;
import com.watchwise.watchwise_api.user.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class NotificationViewAssemblerTest {

    @Mock
    private ContentCardAssembler contentCardAssembler;

    @Mock
    private PersonPreviewResolver personPreviewResolver;

    @Mock
    private NotificationTargetPreviewReader targetPreviewReader;

    @Mock
    private UserRepository userRepository;

    private UUID viewerId;
    private User viewer;
    private NotificationViewAssembler assembler;

    @BeforeEach
    void setUp() {
        viewerId = UUID.randomUUID();
        viewer = User.builder()
                .id(viewerId)
                .preferredLanguage("pt-BR")
                .preferredRegion("BR")
                .build();
        when(userRepository.findById(viewerId)).thenReturn(Optional.of(viewer));
        assembler = new NotificationViewAssembler(
                contentCardAssembler, personPreviewResolver, targetPreviewReader, userRepository);
    }

    @Test
    void shouldAssembleEachContentBearingSystemTypeOnceAndPreserveRawFields() {
        ContentRefDTO movie = content(ContentType.MOVIE, "603", null, null, null);
        ContentRefDTO series = content(ContentType.SERIES, "1399", null, null, null);
        ContentRefDTO season = content(ContentType.SEASON, null, "1399", 1, null);
        ContentRefDTO episode = content(ContentType.EPISODE, null, "1399", 1, 2);
        UserPreviewDTO actor = new UserPreviewDTO(UUID.randomUUID(), "actor", "Actor", "/actor.png", true);
        UUID targetId = UUID.randomUUID();
        List<NotificationResponseDTO> raw = List.of(
                notification(NotificationType.RELEASE, movie, null, actor, null, null, 7, true),
                notification(NotificationType.ANNOUNCED_DATE, series, null, null, null, null, null, false),
                notification(NotificationType.CANCELLED, season, null, null, null, null, null, false),
                notification(NotificationType.RENEWED, episode, null, null, null, null, null, false),
                notification(NotificationType.NEW_EPISODE, movie, null, null,
                        NotificationTargetType.PICKS_TEMPLATE, targetId, 3, false));

        Map<ContentCoordinate, ContentCardDTO> cards = Map.of(
                coordinate(movie), card(movie, ContentPreviewStatus.AVAILABLE),
                coordinate(series), card(series, ContentPreviewStatus.AVAILABLE),
                coordinate(season), card(season, ContentPreviewStatus.AVAILABLE),
                coordinate(episode), card(episode, ContentPreviewStatus.AVAILABLE));
        when(contentCardAssembler.assemble(anyCollection(), any(ContentCardContext.class),
                eq(ContentCardFieldSet.BASIC_METADATA))).thenReturn(cards);
        when(targetPreviewReader.read(anyCollection(), any(ContentCardContext.class))).thenReturn(Map.of());

        List<NotificationViewDTO> result = assembler.assemble(raw, viewerId);

        assertThat(result).hasSize(5);
        assertThat(result).extracting(NotificationViewDTO::type)
                .containsExactly(NotificationType.RELEASE, NotificationType.ANNOUNCED_DATE,
                        NotificationType.CANCELLED, NotificationType.RENEWED, NotificationType.NEW_EPISODE);
        assertThat(result).extracting(NotificationViewDTO::contentCard).doesNotContainNull();
        assertRawFieldsArePreserved(raw.get(0), result.get(0));
        assertRawFieldsArePreserved(raw.get(4), result.get(4));

        ArgumentCaptor<Collection<ContentCardSpec>> specs = ArgumentCaptor.forClass(Collection.class);
        ArgumentCaptor<ContentCardContext> context = ArgumentCaptor.forClass(ContentCardContext.class);
        verify(contentCardAssembler).assemble(specs.capture(), context.capture(),
                eq(ContentCardFieldSet.BASIC_METADATA));
        assertThat(specs.getValue()).extracting(ContentCardSpec::coordinate)
                .containsExactly(coordinate(movie), coordinate(series), coordinate(season), coordinate(episode));
        assertThat(context.getValue().language()).isEqualTo("pt-BR");
        assertThat(context.getValue().region()).isEqualTo("BR");
        assertThat(context.getValue().viewerId()).isEqualTo(viewerId);
        verifyNoInteractions(personPreviewResolver);
    }

    @Test
    void shouldRenderReportTargetAsGenericAndNonNavigableWithoutLoadingSocialTarget() {
        UUID caseId = UUID.randomUUID();
        NotificationResponseDTO report = notification(NotificationType.REPORT_RESOLVED, null, null,
                null, NotificationTargetType.REPORT_CASE, caseId, 1, false);

        NotificationViewDTO result = assembler.assemble(List.of(report), viewerId).getFirst();

        assertThat(result.targetPreview().targetType()).isEqualTo(NotificationTargetType.REPORT_CASE);
        assertThat(result.targetPreview().targetId()).isEqualTo(caseId);
        assertThat(result.targetPreview().title()).isEqualTo("Report");
        assertThat(result.targetPreview().previewStatus()).isEqualTo(ContentPreviewStatus.AVAILABLE);
        verifyNoInteractions(targetPreviewReader, personPreviewResolver, contentCardAssembler);
    }

    @Test
    void shouldResolveDuplicatePeopleAndSocialTargetsInOneBatchWhileKeepingOrder() {
        UUID commentId = UUID.randomUUID();
        UUID diaryId = UUID.randomUUID();
        NotificationResponseDTO first = notification(
                NotificationType.FOLLOWED_PERSON_NEW_CREDIT, null, "42", null, null, null, null, false);
        NotificationResponseDTO second = notification(
                NotificationType.FOLLOWED_PERSON_NEW_CREDIT, null, "42", null, null, null, null, true);
        NotificationResponseDTO third = notification(
                NotificationType.LIKE_RECEIVED, null, null, null, NotificationTargetType.COMMENT,
                commentId, 2, false);
        NotificationResponseDTO fourth = notification(
                NotificationType.COMMENT_RECEIVED, null, null, null, NotificationTargetType.COMMENT,
                commentId, 3, false);
        NotificationResponseDTO fifth = notification(
                NotificationType.COMMENT_RECEIVED, null, null, null, NotificationTargetType.DIARY_ENTRY,
                diaryId, 1, false);
        PersonPreviewDTO person = new PersonPreviewDTO("42", "Ada", "/ada.png", ContentPreviewStatus.AVAILABLE);
        NotificationTargetKey commentKey = new NotificationTargetKey(NotificationTargetType.COMMENT, commentId);
        NotificationTargetKey diaryKey = new NotificationTargetKey(NotificationTargetType.DIARY_ENTRY, diaryId);
        NotificationTargetPreviewDTO commentPreview = new NotificationTargetPreviewDTO(
                NotificationTargetType.COMMENT, commentId, "Comment", "Nice review", null, null, null,
                ContentPreviewStatus.AVAILABLE);
        NotificationTargetPreviewDTO diaryPreview = new NotificationTargetPreviewDTO(
                NotificationTargetType.DIARY_ENTRY, diaryId, "Review", "Great movie", 9, null, null,
                ContentPreviewStatus.PARTIAL);
        when(personPreviewResolver.resolve(eq(List.of("42")))).thenReturn(Map.of("42", person));
        when(targetPreviewReader.read(anyCollection(), any(ContentCardContext.class)))
                .thenReturn(Map.of(commentKey, commentPreview, diaryKey, diaryPreview));

        List<NotificationViewDTO> result = assembler.assemble(List.of(first, second, third, fourth, fifth), viewerId);

        assertThat(result).extracting(NotificationViewDTO::id)
                .containsExactly(first.id(), second.id(), third.id(), fourth.id(), fifth.id());
        assertThat(result.get(0).person()).isSameAs(person);
        assertThat(result.get(1).person()).isSameAs(person);
        assertThat(result.get(2).targetPreview()).isSameAs(commentPreview);
        assertThat(result.get(3).targetPreview()).isSameAs(commentPreview);
        assertThat(result.get(4).targetPreview()).isSameAs(diaryPreview);
        assertRawFieldsArePreserved(third, result.get(2));

        verify(personPreviewResolver).resolve(List.of("42"));
        ArgumentCaptor<Collection<NotificationTargetKey>> keys = ArgumentCaptor.forClass(Collection.class);
        verify(targetPreviewReader).read(keys.capture(), any(ContentCardContext.class));
        assertThat(keys.getValue()).containsExactly(commentKey, diaryKey);
        verifyNoInteractions(contentCardAssembler);
    }

    @Test
    void shouldKeepRowsWhenVisualMetadataIsUnavailableAndWhenNoOptionalMetadataExists() {
        ContentRefDTO content = content(ContentType.MOVIE, "404", null, null, null);
        NotificationResponseDTO contentNotification = notification(
                NotificationType.RELEASE, content, null, null, null, null, null, false);
        NotificationResponseDTO emptyNotification = notification(
                NotificationType.FOLLOW_REQUEST, null, null, null, null, null, null, false);
        PersonPreviewDTO unavailablePerson = new PersonPreviewDTO(
                "404-person", null, null, ContentPreviewStatus.UNAVAILABLE);
        ContentCardDTO unavailableCard = card(content, ContentPreviewStatus.UNAVAILABLE);
        when(contentCardAssembler.assemble(anyCollection(), any(ContentCardContext.class),
                eq(ContentCardFieldSet.BASIC_METADATA)))
                .thenReturn(Map.of(coordinate(content), unavailableCard));
        when(personPreviewResolver.resolve(eq(List.of("404-person"))))
                .thenReturn(Map.of("404-person", unavailablePerson));

        NotificationResponseDTO personNotification = notification(
                NotificationType.FOLLOWED_PERSON_NEW_CREDIT, null, "404-person", null, null, null, null, false);
        List<NotificationViewDTO> result = assembler.assemble(
                List.of(contentNotification, emptyNotification, personNotification), viewerId);

        assertThat(result).hasSize(3);
        assertThat(result.get(0).contentCard().previewStatus()).isEqualTo(ContentPreviewStatus.UNAVAILABLE);
        assertThat(result.get(1).contentCard()).isNull();
        assertThat(result.get(1).person()).isNull();
        assertThat(result.get(1).latestActor()).isNull();
        assertThat(result.get(1).targetPreview()).isNull();
        assertThat(result.get(2).person()).isSameAs(unavailablePerson);
        assertRawFieldsArePreserved(emptyNotification, result.get(1));
    }

    private void assertRawFieldsArePreserved(NotificationResponseDTO expected, NotificationViewDTO actual) {
        assertThat(actual.id()).isEqualTo(expected.id());
        assertThat(actual.type()).isEqualTo(expected.type());
        assertThat(actual.message()).isEqualTo(expected.message());
        assertThat(actual.content()).isSameAs(expected.content());
        assertThat(actual.personTmdbId()).isEqualTo(expected.personTmdbId());
        assertThat(actual.isRead()).isEqualTo(expected.isRead());
        assertThat(actual.createdAt()).isEqualTo(expected.createdAt());
        assertThat(actual.updatedAt()).isEqualTo(expected.updatedAt());
        assertThat(actual.latestActor()).isSameAs(expected.latestActor());
        assertThat(actual.targetType()).isEqualTo(expected.targetType());
        assertThat(actual.targetId()).isEqualTo(expected.targetId());
        assertThat(actual.interactionCount()).isEqualTo(expected.interactionCount());
    }

    private NotificationResponseDTO notification(
            NotificationType type,
            ContentRefDTO content,
            String personTmdbId,
            UserPreviewDTO latestActor,
            NotificationTargetType targetType,
            UUID targetId,
            Integer interactionCount,
            boolean isRead) {
        return new NotificationResponseDTO(
                UUID.randomUUID(), type, type.name(), content, personTmdbId, isRead,
                LocalDateTime.of(2026, 10, 14, 9, 0), LocalDateTime.of(2026, 10, 14, 9, 1),
                latestActor, targetType, targetId, interactionCount);
    }

    private ContentRefDTO content(ContentType type, String tmdbId, String seriesTmdbId,
            Integer seasonNumber, Integer episodeNumber) {
        LocalDateTime timestamp = LocalDateTime.of(2026, 10, 14, 8, 0);
        return new ContentRefDTO(UUID.randomUUID(), tmdbId, type, seriesTmdbId, seasonNumber, episodeNumber,
                false, false, timestamp, timestamp, 120, 120, List.of("Drama"), 2020, List.of("US"));
    }

    private ContentCoordinate coordinate(ContentRefDTO content) {
        return new ContentCoordinate(content.type(), content.tmdbId(), content.seriesTmdbId(),
                content.seasonNumber(), content.episodeNumber());
    }

    private ContentCardDTO card(ContentRefDTO content, ContentPreviewStatus status) {
        return new ContentCardDTO(content.id(), content.type(), content.tmdbId(), content.seriesTmdbId(),
                content.seasonNumber(), content.episodeNumber(), content.type().name(), "/poster.jpg", null,
                null, content.releaseYear(), content.runtimeMinutes(), content.totalRuntimeMinutes(), null, null,
                content.genres(), null, null, status);
    }
}
