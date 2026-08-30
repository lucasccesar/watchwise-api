package com.watchwise.watchwise_api.notification.tracking;

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
import com.watchwise.watchwise_api.user.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Stream;

@Slf4j
@Component
@RequiredArgsConstructor
public class FollowedPersonTrackingJob {

    private final FollowedPersonRepository followedPersonRepository;
    private final TrackedPersonStateRepository trackedPersonStateRepository;
    private final TrackedPersonCreditRepository trackedPersonCreditRepository;
    private final NotificationRepository notificationRepository;
    private final UserRepository userRepository;
    private final ContentService contentService;
    private final ContentRepository contentRepository;
    private final TmdbClient tmdbClient;
    private final NewTransactionExecutor newTransactionExecutor;

    @Scheduled(cron = "${app.followed-person-tracking.cron}")
    public void run() {
        followedPersonRepository.findDistinctPersonTmdbIds().forEach(this::processPerson);
    }

    private void processPerson(String personTmdbId) {
        try {
            Optional<TmdbPersonCredits> fresh = tmdbClient.getPersonCombinedCredits(personTmdbId);
            if (fresh.isEmpty()) {
                return;
            }

            newTransactionExecutor.runInNewTransaction(() -> {
                TrackedPersonState state = trackedPersonStateRepository.findByPersonTmdbId(personTmdbId)
                        .orElseGet(() -> TrackedPersonState.builder().personTmdbId(personTmdbId).build());
                state.setLastCheckedAt(LocalDateTime.now());
                trackedPersonStateRepository.save(state);

                Stream.concat(fresh.get().cast().stream(), fresh.get().crew().stream())
                        .distinct()
                        .forEach(credit -> processCredit(personTmdbId, state, credit));
                return null;
            });
        } catch (RuntimeException e) {
            log.warn("Failed to process followed person {}: {}", personTmdbId, e.getMessage());
        }
    }

    private void processCredit(String personTmdbId, TrackedPersonState state, TmdbCredit credit) {
        if (trackedPersonCreditRepository.existsByTrackedPersonStateIdAndCreditTmdbId(state.getId(), credit.id())) {
            return;
        }

        ContentType creditType = "movie".equals(credit.mediaType()) ? ContentType.MOVIE : ContentType.SERIES;
        ContentRefDTO contentRef = contentService.getOrCreateReference(
                new ContentRefCreationDTO(credit.id(), creditType, null, null, null, null, null, null, null, null, null));

        notifyFollowers(personTmdbId, contentRef);

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
