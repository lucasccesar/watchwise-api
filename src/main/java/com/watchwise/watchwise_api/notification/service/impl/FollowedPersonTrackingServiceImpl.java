package com.watchwise.watchwise_api.notification.service.impl;

import com.watchwise.watchwise_api.common.tmdb.TmdbClient;
import com.watchwise.watchwise_api.common.tmdb.TmdbCredit;
import com.watchwise.watchwise_api.common.tmdb.TmdbPersonCredits;
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
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Stream;

@Slf4j
@Service
@RequiredArgsConstructor
public class FollowedPersonTrackingServiceImpl implements FollowedPersonTrackingService {

    private final FollowedPersonRepository followedPersonRepository;
    private final TrackedPersonStateRepository trackedPersonStateRepository;
    private final TrackedPersonCreditRepository trackedPersonCreditRepository;
    private final NotificationRepository notificationRepository;
    private final UserRepository userRepository;
    private final ContentService contentService;
    private final ContentRepository contentRepository;
    private final TmdbClient tmdbClient;
    private final NewTransactionExecutor newTransactionExecutor;

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

            Optional<TrackedPersonState> existingState = trackedPersonStateRepository.findByPersonTmdbId(personTmdbId);
            boolean isFirstCheck = existingState.isEmpty();

            newTransactionExecutor.runInNewTransaction(() -> {
                TrackedPersonState state = existingState
                        .orElseGet(() -> TrackedPersonState.builder().personTmdbId(personTmdbId).build());
                state.setLastCheckedAt(LocalDateTime.now());
                trackedPersonStateRepository.save(state);

                Stream.concat(fresh.get().cast().stream(), fresh.get().crew().stream())
                        .distinct()
                        .forEach(credit -> processCredit(personTmdbId, state, credit, !isFirstCheck));
                return null;
            });
        } catch (RuntimeException e) {
            log.warn("Failed to process followed person {}: {}", personTmdbId, e.getMessage());
        }
    }

    private void processCredit(String personTmdbId, TrackedPersonState state, TmdbCredit credit, boolean notify) {
        if (trackedPersonCreditRepository.existsByTrackedPersonStateIdAndCreditTmdbId(state.getId(), credit.id())) {
            return;
        }

        ContentType creditType = "movie".equals(credit.mediaType()) ? ContentType.MOVIE : ContentType.SERIES;
        ContentRefDTO contentRef = contentService.getOrCreateReference(
                new ContentRefCreationDTO(credit.id(), creditType, null, null, null, null, null, null, null, null, null));

        if (notify) {
            notifyFollowers(personTmdbId, contentRef);
        }

        trackedPersonCreditRepository.save(TrackedPersonCredit.builder()
                .trackedPersonState(state)
                .creditTmdbId(credit.id())
                .creditType(creditType)
                .build());
    }

    private void notifyFollowers(String personTmdbId, ContentRefDTO contentRef) {
        List<UUID> followerIds = followedPersonRepository.findUserIdsByPersonTmdbId(personTmdbId);
        LocalDateTime now = LocalDateTime.now();
        Content content = contentRepository.getReferenceById(contentRef.id());

        followerIds.forEach(userId -> notificationRepository.save(Notification.builder()
                .user(userRepository.getReferenceById(userId))
                .type(NotificationType.FOLLOWED_PERSON_NEW_CREDIT)
                .message("New title from someone you follow")
                .content(content)
                .personTmdbId(personTmdbId)
                .isRead(false)
                .createdAt(now)
                .updatedAt(now)
                .build()));
    }
}
