package com.watchwise.watchwise_api.diaryentry.controller;

import com.watchwise.watchwise_api.common.dto.PageResponseDTO;
import com.watchwise.watchwise_api.common.dto.PageWithBackdropResponseDTO;
import com.watchwise.watchwise_api.common.exception.BadRequestException;
import com.watchwise.watchwise_api.common.security.RequestThrottler;
import com.watchwise.watchwise_api.content.entity.ContentType;
import com.watchwise.watchwise_api.diaryentry.dto.ContentReviewResponseDTO;
import com.watchwise.watchwise_api.diaryentry.dto.DiaryEntryBulkCreationDTO;
import com.watchwise.watchwise_api.diaryentry.dto.DiaryEntryCreationDTO;
import com.watchwise.watchwise_api.diaryentry.dto.DiaryEntryCreationResultDTO;
import com.watchwise.watchwise_api.diaryentry.dto.DiaryEntriesBatchRequestDTO;
import com.watchwise.watchwise_api.diaryentry.dto.DiaryEntryResponseDTO;
import com.watchwise.watchwise_api.diaryentry.dto.DiaryDaySummaryDTO;
import com.watchwise.watchwise_api.diaryentry.dto.DiaryTimelineCompletionItemDTO;
import com.watchwise.watchwise_api.diaryentry.dto.DiaryTimelineEntryItemDTO;
import com.watchwise.watchwise_api.diaryentry.dto.DiaryTimelineResponseItemDTO;
import com.watchwise.watchwise_api.diaryentry.dto.DiaryEntryUpdateDTO;
import com.watchwise.watchwise_api.diaryentry.dto.DiarySeriesOptionDTO;
import com.watchwise.watchwise_api.diaryentry.dto.DiaryViewFilter;
import com.watchwise.watchwise_api.diaryentry.dto.DiaryViewResponseItemDTO;
import com.watchwise.watchwise_api.diaryentry.dto.SeriesInProgressPageResponseDTO;
import com.watchwise.watchwise_api.diaryentry.dto.SeriesInProgressResponseDTO;
import com.watchwise.watchwise_api.diaryentry.repository.ContentReviewSort;
import com.watchwise.watchwise_api.diaryentry.repository.DiaryEntrySort;
import com.watchwise.watchwise_api.diaryentry.service.DiaryEntryService;
import com.watchwise.watchwise_api.diaryentry.service.DiaryViewService;
import com.watchwise.watchwise_api.seriesprogress.repository.SeriesProgressReadRepository;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;

import java.time.Duration;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

@RestController
@RequiredArgsConstructor
public class DiaryEntryController {

    private final DiaryEntryService diaryEntryService;
    private final DiaryViewService diaryViewService;
    private final RequestThrottler requestThrottler;

    @Value("${app.rate-limit.diary-action.max-requests}")
    private int diaryActionMaxRequests;
    @Value("${app.rate-limit.diary-action.window-minutes}")
    private long diaryActionWindowMinutes;

    @Value("${app.rate-limit.diary-bulk-action.max-requests}")
    private int diaryBulkActionMaxRequests;
    @Value("${app.rate-limit.diary-bulk-action.window-minutes}")
    private long diaryBulkActionWindowMinutes;

    @GetMapping("/users/{userId}/reviews")
    public ResponseEntity<PageResponseDTO<ContentReviewResponseDTO>> getUserReviews(
            @PathVariable UUID userId,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size) {
        Page<ContentReviewResponseDTO> reviews = diaryEntryService.getUserReviews(
                getCurrentUserId(), userId, page, size);
        return ResponseEntity.ok(PageResponseDTO.of(reviews));
    }

    @GetMapping("/users/{userId}/diary")
    public ResponseEntity<?> getDiaryEntries(
            @PathVariable UUID userId,
            @RequestParam(defaultValue = "false") boolean includeCompletions,
            @RequestParam(required = false) Integer year,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size,
            @RequestParam(required = false) ContentType type,
            @RequestParam(required = false) LocalDate dateFrom,
            @RequestParam(required = false) LocalDate dateTo,
            @RequestParam(required = false) Boolean hasReview,
            @RequestParam(required = false) String seriesTmdbId,
            @RequestParam(required = false) Integer score,
            @RequestParam(required = false) Integer scoreFrom,
            @RequestParam(required = false) Integer scoreTo,
            @RequestParam(required = false, defaultValue = "NEWEST") DiaryEntrySort sortBy
    ) {
        if (!includeCompletions) {
            Page<DiaryEntryResponseDTO> entries = diaryEntryService.getDiaryEntries(
                    getCurrentUserId(), userId, year, page, size, type, dateFrom, dateTo, hasReview, seriesTmdbId,
                    score, scoreFrom, scoreTo, sortBy);
            String backdropPath = entries.isEmpty() || entries.getContent().get(0).card() == null
                    ? null
                    : entries.getContent().get(0).card().backdropPath();
            return ResponseEntity.ok(PageWithBackdropResponseDTO.of(entries, backdropPath));
        }

        Page<DiaryTimelineResponseItemDTO> entries = diaryEntryService.getDiaryTimeline(
                getCurrentUserId(), userId, year, page, size, type, dateFrom, dateTo, hasReview, seriesTmdbId, score,
                scoreFrom, scoreTo, sortBy);
        String backdropPath = null;
        if (!entries.isEmpty()) {
            DiaryTimelineResponseItemDTO firstItem = entries.getContent().getFirst();
            if (firstItem instanceof DiaryTimelineEntryItemDTO persisted && persisted.entry().card() != null) {
                backdropPath = persisted.entry().card().backdropPath();
            } else if (firstItem instanceof DiaryTimelineCompletionItemDTO completion) {
                backdropPath = completion.seriesCard() != null
                        ? completion.seriesCard().backdropPath()
                        : completion.card() == null ? null : completion.card().backdropPath();
            }
        }
        return ResponseEntity.ok(PageWithBackdropResponseDTO.of(entries, backdropPath));
    }

    @PostMapping("/users/{userId}/diary/batch")
    public ResponseEntity<List<DiaryEntryResponseDTO>> getDiaryEntriesByIds(
            @PathVariable UUID userId,
            @Valid @RequestBody DiaryEntriesBatchRequestDTO request) {
        return ResponseEntity.ok(diaryEntryService.getDiaryEntriesByIds(
                getCurrentUserId(), userId, request.entryIds()));
    }

    @GetMapping("/users/{userId}/diary/view")
    public ResponseEntity<?> getDiaryView(
            @PathVariable UUID userId,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size,
            @RequestParam(required = false) ContentType type,
            @RequestParam(required = false) LocalDate dateStart,
            @RequestParam(required = false) LocalDate dateEnd,
            @RequestParam(required = false) Integer scoreMin,
            @RequestParam(required = false) Integer scoreMax,
            @RequestParam(required = false) Boolean hasReview,
            @RequestParam(required = false) String seriesTmdbId,
            @RequestParam(required = false) Boolean watchedInTheater,
            @RequestParam(defaultValue = "NEWEST") DiaryEntrySort sortBy,
            @RequestParam(defaultValue = "false") boolean grouped,
            @RequestParam(required = false) String cursor) {
        boolean cursorGroupedView = grouped && !Boolean.TRUE.equals(hasReview);
        if (cursor != null && !cursorGroupedView) {
            throw new BadRequestException("cursor requires grouped=true and hasReview=false");
        }
        if (cursorGroupedView && page != null && page > 1) {
            throw new BadRequestException("Use cursor instead of page for grouped diary views");
        }
        DiaryViewFilter filter = new DiaryViewFilter(type, dateStart, dateEnd, scoreMin, scoreMax, hasReview,
                seriesTmdbId, watchedInTheater, sortBy);
        if (cursorGroupedView) {
            return ResponseEntity.ok(diaryViewService.getGroupedView(
                    getCurrentUserId(), userId, filter, cursor, size));
        }
        Page<DiaryViewResponseItemDTO> view = diaryViewService.getView(
                getCurrentUserId(), userId, filter, page, size, false);
        return ResponseEntity.ok(PageResponseDTO.of(view));
    }

    /**
     * Compatibility overload for callers compiled against the pre-sort Java API.
     */
    public ResponseEntity<PageResponseDTO<DiaryEntryResponseDTO>> getDiaryEntries(
            UUID userId,
            Integer year,
            Integer page,
            Integer size,
            ContentType type,
            LocalDate dateFrom,
            LocalDate dateTo,
            Boolean hasReview,
            String seriesTmdbId,
            Integer score) {
        Page<DiaryEntryResponseDTO> entries = diaryEntryService.getDiaryEntries(
                getCurrentUserId(), userId, year, page, size, type, dateFrom, dateTo, hasReview, seriesTmdbId, score);
        return ResponseEntity.ok(PageResponseDTO.of(entries));
    }

    @GetMapping("/diary/{diaryEntryId}")
    public ResponseEntity<DiaryEntryResponseDTO> getDiaryEntry(@PathVariable UUID diaryEntryId) {
        return ResponseEntity.ok(diaryEntryService.getDiaryEntry(getCurrentUserId(), diaryEntryId));
    }

    @GetMapping("/users/{userId}/diary/daily-summary")
    public ResponseEntity<List<DiaryDaySummaryDTO>> getDiaryDailySummary(
            @PathVariable UUID userId,
            @RequestParam(required = false) ContentType type,
            @RequestParam(required = false) LocalDate dateFrom,
            @RequestParam(required = false) LocalDate dateTo,
            @RequestParam(required = false) Boolean hasReview,
            @RequestParam(required = false) String seriesTmdbId,
            @RequestParam(required = false) Integer score,
            @RequestParam(required = false) Integer scoreFrom,
            @RequestParam(required = false) Integer scoreTo) {
        return ResponseEntity.ok(diaryEntryService.getDiaryDailySummary(
                getCurrentUserId(), userId, type, dateFrom, dateTo, hasReview, seriesTmdbId, score, scoreFrom, scoreTo));
    }

    @GetMapping("/users/{userId}/diary/series")
    public ResponseEntity<List<DiarySeriesOptionDTO>> getDiarySeriesOptions(@PathVariable UUID userId) {
        return ResponseEntity.ok(diaryEntryService.getDiarySeriesOptions(getCurrentUserId(), userId));
    }

    @GetMapping("/contents/{contentId}/reviews")
    public ResponseEntity<PageResponseDTO<ContentReviewResponseDTO>> getReviewsForContent(
            @PathVariable UUID contentId,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size,
            @RequestParam(required = false, defaultValue = "RECENT") ContentReviewSort sort
    ) {
        Page<ContentReviewResponseDTO> reviews = diaryEntryService.getReviewsForContent(
                getCurrentUserId(), contentId, sort, page, size);
        return ResponseEntity.ok(PageResponseDTO.of(reviews));
    }

    public ResponseEntity<PageResponseDTO<ContentReviewResponseDTO>> getReviewsForContent(
            UUID contentId, Integer page, Integer size) {
        Page<ContentReviewResponseDTO> reviews = diaryEntryService.getReviewsForContent(
                getCurrentUserId(), contentId, page, size);
        return ResponseEntity.ok(PageResponseDTO.of(reviews));
    }

    @GetMapping("/users/{userId}/series-in-progress")
    public ResponseEntity<SeriesInProgressPageResponseDTO> getSeriesInProgress(
            @PathVariable UUID userId,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size,
            @RequestParam(required = false, defaultValue = "LAST_WATCHED")
            SeriesProgressReadRepository.SeriesProgressSort sortBy,
            @RequestParam(required = false, defaultValue = "DESC") Sort.Direction direction
    ) {
        SeriesInProgressPageResponseDTO response = diaryEntryService.getSeriesInProgress(
                getCurrentUserId(), userId, page, size, sortBy, direction);
        return ResponseEntity.ok(response);
    }

    /**
     * Compatibility overload for callers compiled against the pre-Task-4 Java API.
     */
    public ResponseEntity<PageResponseDTO<SeriesInProgressResponseDTO>> getSeriesInProgress(
            UUID userId, Integer page, Integer size) {
        Page<SeriesInProgressResponseDTO> entries = diaryEntryService.getSeriesInProgress(
                getCurrentUserId(), userId, page, size);
        return ResponseEntity.ok(PageResponseDTO.of(entries));
    }

    @PostMapping("/diary")
    public ResponseEntity<DiaryEntryCreationResultDTO> createDiaryEntry(@Valid @RequestBody DiaryEntryCreationDTO diaryEntryCreationDTO) {
        requestThrottler.checkAllowed(diaryActionKey(), diaryActionMaxRequests, Duration.ofMinutes(diaryActionWindowMinutes));

        DiaryEntryCreationResultDTO created = diaryEntryService.createDiaryEntry(getCurrentUserId(), diaryEntryCreationDTO);
        return ResponseEntity.status(HttpStatus.CREATED).body(created);
    }

    @PostMapping("/diary/bulk")
    public ResponseEntity<DiaryEntryCreationResultDTO> createDiaryEntriesInBulk(
            @Valid @RequestBody DiaryEntryBulkCreationDTO diaryEntryBulkCreationDTO,
            @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey) {
        requestThrottler.checkAllowed(diaryBulkActionKey(), diaryBulkActionMaxRequests, Duration.ofMinutes(diaryBulkActionWindowMinutes));

        DiaryEntryCreationResultDTO created = diaryEntryService.createDiaryEntriesInBulk(
                getCurrentUserId(), diaryEntryBulkCreationDTO, idempotencyKey);
        return ResponseEntity.status(HttpStatus.CREATED).body(created);
    }

    @PatchMapping("/diary/{diaryEntryId}")
    public ResponseEntity<DiaryEntryResponseDTO> updateDiaryEntry(
            @PathVariable UUID diaryEntryId,
            @Valid @RequestBody DiaryEntryUpdateDTO diaryEntryUpdateDTO
    ) {
        requestThrottler.checkAllowed(diaryActionKey(), diaryActionMaxRequests, Duration.ofMinutes(diaryActionWindowMinutes));

        DiaryEntryResponseDTO updated = diaryEntryService.updateDiaryEntry(getCurrentUserId(), diaryEntryId, diaryEntryUpdateDTO);
        return ResponseEntity.ok(updated);
    }

    @DeleteMapping("/diary/{diaryEntryId}")
    public ResponseEntity<Void> deleteDiaryEntry(@PathVariable UUID diaryEntryId) {
        requestThrottler.checkAllowed(diaryActionKey(), diaryActionMaxRequests, Duration.ofMinutes(diaryActionWindowMinutes));

        diaryEntryService.deleteDiaryEntry(getCurrentUserId(), diaryEntryId);
        return ResponseEntity.noContent().build();
    }

    @DeleteMapping("/diary/series/{seriesTmdbId}")
    public ResponseEntity<Void> deleteAllDiaryEntriesForSeries(@PathVariable String seriesTmdbId) {
        requestThrottler.checkAllowed(diaryBulkActionKey(), diaryBulkActionMaxRequests, Duration.ofMinutes(diaryBulkActionWindowMinutes));

        diaryEntryService.deleteAllDiaryEntriesForSeries(getCurrentUserId(), seriesTmdbId);
        return ResponseEntity.noContent().build();
    }

    private String diaryActionKey() {
        return "diary-action|" + getCurrentUserId();
    }

    private String diaryBulkActionKey() {
        return "diary-bulk-action|" + getCurrentUserId();
    }

    private UUID getCurrentUserId() {
        return (UUID) SecurityContextHolder.getContext().getAuthentication().getPrincipal();
    }
}
