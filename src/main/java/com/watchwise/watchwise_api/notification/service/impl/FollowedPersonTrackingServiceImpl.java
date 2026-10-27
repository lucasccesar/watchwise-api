package com.watchwise.watchwise_api.notification.service.impl;

import com.watchwise.watchwise_api.common.tmdb.TmdbClient;
import com.watchwise.watchwise_api.common.tmdb.TmdbCredit;
import com.watchwise.watchwise_api.common.tmdb.TmdbPersonCredits;
import com.watchwise.watchwise_api.common.transaction.AdvisoryLock;
import com.watchwise.watchwise_api.common.transaction.NewTransactionExecutor;
import com.watchwise.watchwise_api.content.dto.ContentRefCreationDTO;
import com.watchwise.watchwise_api.content.dto.ContentRefDTO;
import com.watchwise.watchwise_api.content.entity.Content;
import com.watchwise.watchwise_api.content.entity.ContentType;
import com.watchwise.watchwise_api.content.repository.ContentRepository;
import com.watchwise.watchwise_api.content.service.ContentService;
import com.watchwise.watchwise_api.followedperson.repository.FollowedPersonRepository;
import com.watchwise.watchwise_api.notification.entity.Notification;
import com.watchwise.watchwise_api.notification.entity.NotificationType;
import com.watchwise.watchwise_api.notification.entity.TrackedPersonCredit;
import com.watchwise.watchwise_api.notification.entity.TrackedPersonState;
import com.watchwise.watchwise_api.notification.repository.NotificationRepository;
import com.watchwise.watchwise_api.notification.repository.TrackedPersonCreditRepository;
import com.watchwise.watchwise_api.notification.repository.TrackedPersonStateRepository;
import com.watchwise.watchwise_api.notification.service.FollowedPersonTrackingService;
import com.watchwise.watchwise_api.user.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Stream;

@Slf4j
@Service
@RequiredArgsConstructor
public class FollowedPersonTrackingServiceImpl implements FollowedPersonTrackingService {

    private static final String PERSON_LOCK_PREFIX = "followed-person-tracking:";

    private final FollowedPersonRepository followedPersonRepository;
    private final TrackedPersonStateRepository trackedPersonStateRepository;
    private final TrackedPersonCreditRepository trackedPersonCreditRepository;
    private final NotificationRepository notificationRepository;
    private final UserRepository userRepository;
    private final ContentService contentService;
    private final ContentRepository contentRepository;
    private final TmdbClient tmdbClient;
    private final NewTransactionExecutor newTransactionExecutor;
    private final AdvisoryLock advisoryLock;

    @Override
    public void trackFollowedPeopleCredits() {
        followedPersonRepository.findDistinctPersonTmdbIds().forEach(this::processPerson);
    }

    private void processPerson(String personTmdbId) {
        try {
            Optional<TmdbPersonCredits> fresh = tmdbClient.getPersonCombinedCredits(personTmdbId);
            if (fresh.isEmpty()) {
                return;
            }

            for (int attempt = 0; attempt < 2; attempt++) {
                try {
                    processPersonCredits(personTmdbId, fresh.get());
                    return;
                } catch (TrackingStateChangedException e) {
                    if (attempt == 1) {
                        log.warn("Tracked state for followed person {} changed during both attempts; leaving it for the next scheduled run",
                                personTmdbId);
                    }
                }
            }
        } catch (RuntimeException e) {
            log.warn("Failed to process followed person {}: {}", personTmdbId, e.getMessage());
        }
    }

    private void processPersonCredits(String personTmdbId, TmdbPersonCredits fresh) {
        Optional<TrackedPersonState> stateSnapshot = trackedPersonStateRepository.findByPersonTmdbId(personTmdbId);
        UUID stateIdSnapshot = stateSnapshot.map(TrackedPersonState::getId).orElse(null);
        boolean isBaseline = stateIdSnapshot == null;
        List<TrackedCreditKey> creditKeys = supportedCreditKeys(personTmdbId, fresh);

        Map<TrackedCreditKey, UUID> candidateContentIds = new HashMap<>();
        if (!isBaseline) {
            Set<TrackedCreditKey> preflightKnownKeys = loadKnownKeys(stateIdSnapshot);
            for (TrackedCreditKey key : creditKeys) {
                if (preflightKnownKeys.contains(key)) {
                    continue;
                }
                ContentRefDTO contentRef = contentService.getOrCreateReference(
                        new ContentRefCreationDTO(key.tmdbId(), key.type(), null, null, null, null, null));
                candidateContentIds.put(key, contentRef.id());
            }
        }

        newTransactionExecutor.runInNewTransaction(() -> {
            advisoryLock.lock(PERSON_LOCK_PREFIX + personTmdbId);

            Optional<TrackedPersonState> currentState = trackedPersonStateRepository.findByPersonTmdbId(personTmdbId);
            UUID currentStateId = currentState.map(TrackedPersonState::getId).orElse(null);
            if (!java.util.Objects.equals(stateIdSnapshot, currentStateId)) {
                throw new TrackingStateChangedException();
            }

            boolean isNewState = currentState.isEmpty();
            TrackedPersonState state = currentState.orElseGet(() -> TrackedPersonState.builder()
                    .personTmdbId(personTmdbId)
                    .build());
            state.setLastCheckedAt(LocalDateTime.now());
            if (isNewState) {
                state = trackedPersonStateRepository.save(state);
            }

            Set<TrackedCreditKey> knownKeys = loadKnownKeys(state.getId());
            for (TrackedCreditKey key : creditKeys) {
                if (!knownKeys.add(key)) {
                    continue;
                }

                trackedPersonCreditRepository.save(TrackedPersonCredit.builder()
                        .trackedPersonState(state)
                        .creditTmdbId(key.tmdbId())
                        .creditType(key.type())
                        .build());

                UUID contentId = candidateContentIds.get(key);
                if (contentId != null) {
                    notifyFollowers(personTmdbId, contentId);
                }
            }
            return null;
        });
    }

    private Set<TrackedCreditKey> loadKnownKeys(UUID trackedPersonStateId) {
        return trackedPersonCreditRepository.findByTrackedPersonStateId(trackedPersonStateId).stream()
                .map(credit -> new TrackedCreditKey(credit.getCreditType(), credit.getCreditTmdbId()))
                .collect(java.util.stream.Collectors.toCollection(HashSet::new));
    }

    private List<TrackedCreditKey> supportedCreditKeys(String personTmdbId, TmdbPersonCredits credits) {
        return Stream.concat(credits.cast().stream(), credits.crew().stream())
                .distinct()
                .map(credit -> toTrackedCreditKey(personTmdbId, credit))
                .flatMap(Optional::stream)
                .distinct()
                .toList();
    }

    private Optional<TrackedCreditKey> toTrackedCreditKey(String personTmdbId, TmdbCredit credit) {
        if (credit.id() == null || credit.id().isBlank()) {
            log.debug("Ignoring followed-person credit without a TMDB ID for person {}", personTmdbId);
            return Optional.empty();
        }

        ContentType type = switch (credit.mediaType() == null ? "" : credit.mediaType()) {
            case "movie" -> ContentType.MOVIE;
            case "tv" -> ContentType.SERIES;
            default -> {
                log.debug("Ignoring followed-person credit with an unsupported media type for person {}", personTmdbId);
                yield null;
            }
        };
        return type == null ? Optional.empty() : Optional.of(new TrackedCreditKey(type, credit.id()));
    }

    private void notifyFollowers(String personTmdbId, UUID contentId) {
        List<UUID> followerIds = followedPersonRepository.findUserIdsByPersonTmdbId(personTmdbId);
        LocalDateTime now = LocalDateTime.now();
        Content content = contentRepository.getReferenceById(contentId);

        List<Notification> notifications = followerIds.stream()
                .map(userId -> Notification.builder()
                        .user(userRepository.getReferenceById(userId))
                        .type(NotificationType.FOLLOWED_PERSON_NEW_CREDIT)
                        .message("New title from someone you follow")
                        .content(content)
                        .personTmdbId(personTmdbId)
                        .isRead(false)
                        .createdAt(now)
                        .updatedAt(now)
                        .build())
                .toList();
        notificationRepository.saveAll(notifications);
    }

    private record TrackedCreditKey(ContentType type, String tmdbId) {
    }

    private static class TrackingStateChangedException extends RuntimeException {
    }
}
