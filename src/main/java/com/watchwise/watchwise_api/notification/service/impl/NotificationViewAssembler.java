package com.watchwise.watchwise_api.notification.service.impl;

import com.watchwise.watchwise_api.common.exception.NotFoundException;
import com.watchwise.watchwise_api.content.dto.ContentCardDTO;
import com.watchwise.watchwise_api.content.dto.ContentCardFieldSet;
import com.watchwise.watchwise_api.content.dto.ContentPreviewStatus;
import com.watchwise.watchwise_api.content.dto.ContentRefDTO;
import com.watchwise.watchwise_api.content.service.ContentCardContext;
import com.watchwise.watchwise_api.content.service.ContentCardSpec;
import com.watchwise.watchwise_api.content.service.ContentCoordinate;
import com.watchwise.watchwise_api.content.service.impl.ContentCardAssembler;
import com.watchwise.watchwise_api.notification.dto.NotificationResponseDTO;
import com.watchwise.watchwise_api.notification.dto.NotificationTargetPreviewDTO;
import com.watchwise.watchwise_api.notification.dto.NotificationViewDTO;
import com.watchwise.watchwise_api.person.dto.PersonPreviewDTO;
import com.watchwise.watchwise_api.person.service.PersonPreviewResolver;
import com.watchwise.watchwise_api.user.entity.User;
import com.watchwise.watchwise_api.user.repository.UserRepository;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

@Component
public class NotificationViewAssembler {

    private static final String DEFAULT_LANGUAGE = "en-US";
    private static final String DEFAULT_REGION = "US";

    private final ContentCardAssembler contentCardAssembler;
    private final PersonPreviewResolver personPreviewResolver;
    private final NotificationTargetPreviewReader targetPreviewReader;
    private final UserRepository userRepository;

    public NotificationViewAssembler(
            ContentCardAssembler contentCardAssembler,
            PersonPreviewResolver personPreviewResolver,
            NotificationTargetPreviewReader targetPreviewReader,
            UserRepository userRepository) {
        this.contentCardAssembler = contentCardAssembler;
        this.personPreviewResolver = personPreviewResolver;
        this.targetPreviewReader = targetPreviewReader;
        this.userRepository = userRepository;
    }

    public List<NotificationViewDTO> assemble(
            List<NotificationResponseDTO> notifications,
            UUID viewerId) {
        if (notifications == null || notifications.isEmpty()) {
            return List.of();
        }

        User viewer = userRepository.findById(viewerId)
                .orElseThrow(() -> new NotFoundException("User not found"));
        ContentCardContext context = new ContentCardContext(
                preferredLanguage(viewer), preferredRegion(viewer), null, viewerId);
        Map<ContentCoordinate, ContentRefDTO> contentByCoordinate = distinctContents(notifications);
        Map<ContentCoordinate, ContentCardDTO> cards = assembleContentCards(contentByCoordinate, context);
        Map<String, PersonPreviewDTO> people = resolvePeople(notifications);
        Map<NotificationTargetKey, NotificationTargetPreviewDTO> targets = resolveTargets(notifications, context);

        return notifications.stream()
                .map(notification -> toView(notification, cards, people, targets))
                .toList();
    }

    private String preferredLanguage(User viewer) {
        return viewer.getPreferredLanguage() == null ? DEFAULT_LANGUAGE : viewer.getPreferredLanguage();
    }

    private String preferredRegion(User viewer) {
        return viewer.getPreferredRegion() == null ? DEFAULT_REGION : viewer.getPreferredRegion();
    }

    private Map<ContentCoordinate, ContentRefDTO> distinctContents(
            Collection<NotificationResponseDTO> notifications) {
        Map<ContentCoordinate, ContentRefDTO> contents = new LinkedHashMap<>();
        notifications.stream()
                .map(NotificationResponseDTO::content)
                .filter(Objects::nonNull)
                .filter(content -> content.type() != null)
                .forEach(content -> contents.putIfAbsent(toCoordinate(content), content));
        return contents;
    }

    private Map<ContentCoordinate, ContentCardDTO> assembleContentCards(
            Map<ContentCoordinate, ContentRefDTO> contentByCoordinate,
            ContentCardContext context) {
        if (contentByCoordinate.isEmpty()) {
            return Map.of();
        }

        List<ContentCardSpec> specs = contentByCoordinate.values().stream()
                .map(content -> new ContentCardSpec(
                        toCoordinate(content), null, null, null, content.runtimeMinutes(), content.genres()))
                .toList();
        Map<ContentCoordinate, ContentCardDTO> cards = contentCardAssembler.assemble(
                specs, context, ContentCardFieldSet.BASIC_METADATA);
        return cards == null ? Map.of() : cards;
    }

    private Map<String, PersonPreviewDTO> resolvePeople(Collection<NotificationResponseDTO> notifications) {
        List<String> personIds = notifications.stream()
                .map(NotificationResponseDTO::personTmdbId)
                .filter(this::hasText)
                .distinct()
                .toList();
        if (personIds.isEmpty()) {
            return Map.of();
        }
        Map<String, PersonPreviewDTO> people = personPreviewResolver.resolve(personIds);
        return people == null ? Map.of() : people;
    }

    private Map<NotificationTargetKey, NotificationTargetPreviewDTO> resolveTargets(
            Collection<NotificationResponseDTO> notifications,
            ContentCardContext context) {
        Set<NotificationTargetKey> keys = new LinkedHashSet<>();
        notifications.stream()
                .map(this::targetKey)
                .filter(Objects::nonNull)
                .forEach(keys::add);
        if (keys.isEmpty()) {
            return Map.of();
        }
        Map<NotificationTargetKey, NotificationTargetPreviewDTO> targets = targetPreviewReader.read(keys, context);
        return targets == null ? Map.of() : targets;
    }

    private NotificationViewDTO toView(
            NotificationResponseDTO notification,
            Map<ContentCoordinate, ContentCardDTO> cards,
            Map<String, PersonPreviewDTO> people,
            Map<NotificationTargetKey, NotificationTargetPreviewDTO> targets) {
        ContentRefDTO content = notification.content();
        ContentCoordinate coordinate = content == null || content.type() == null ? null : toCoordinate(content);
        ContentCardDTO contentCard = coordinate == null ? null : cards.get(coordinate);
        if (coordinate != null && contentCard == null) {
            contentCard = unavailableCard(content, coordinate);
        }

        PersonPreviewDTO person = hasText(notification.personTmdbId())
                ? people.getOrDefault(notification.personTmdbId(), unavailablePerson(notification.personTmdbId()))
                : null;
        NotificationTargetKey targetKey = targetKey(notification);
        NotificationTargetPreviewDTO targetPreview = targetKey == null
                ? null
                : targets.getOrDefault(targetKey, unavailableTarget(targetKey));

        return new NotificationViewDTO(
                notification.id(), notification.type(), notification.message(), notification.content(),
                notification.personTmdbId(), notification.isRead(), notification.createdAt(), notification.updatedAt(),
                notification.latestActor(), notification.targetType(), notification.targetId(),
                notification.interactionCount(), contentCard, person, targetPreview);
    }

    private ContentCoordinate toCoordinate(ContentRefDTO content) {
        return new ContentCoordinate(content.type(), content.tmdbId(), content.seriesTmdbId(),
                content.seasonNumber(), content.episodeNumber());
    }

    private NotificationTargetKey targetKey(NotificationResponseDTO notification) {
        if (notification.targetType() == null || notification.targetId() == null) {
            return null;
        }
        return new NotificationTargetKey(notification.targetType(), notification.targetId());
    }

    private ContentCardDTO unavailableCard(ContentRefDTO content, ContentCoordinate coordinate) {
        return new ContentCardDTO(
                content.id(), coordinate.type(), coordinate.tmdbId(), coordinate.seriesTmdbId(),
                coordinate.seasonNumber(), coordinate.episodeNumber(), null, null, null, null, null, null,
                null, null, null, null, null, null, ContentPreviewStatus.UNAVAILABLE);
    }

    private PersonPreviewDTO unavailablePerson(String personTmdbId) {
        return new PersonPreviewDTO(personTmdbId, null, null, ContentPreviewStatus.UNAVAILABLE);
    }

    private NotificationTargetPreviewDTO unavailableTarget(NotificationTargetKey key) {
        return new NotificationTargetPreviewDTO(
                key.targetType(), key.targetId(), null, null, null, null, null, ContentPreviewStatus.UNAVAILABLE);
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
