package com.watchwise.watchwise_api.platformimport.service.impl;

import com.watchwise.watchwise_api.diaryentry.repository.DiaryEntryRepository;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;

@Service
public class ImportPassPlanner {

    private static final int MAX_EVENTS_PER_PLAN = 200;
    private static final int MAX_EXISTING_CANDIDATES = 200;

    private final DiaryEntryRepository diaryEntryRepository;
    private final JdbcTemplate jdbcTemplate;

    public ImportPassPlanner(DiaryEntryRepository diaryEntryRepository, JdbcTemplate jdbcTemplate) {
        this.diaryEntryRepository = diaryEntryRepository;
        this.jdbcTemplate = jdbcTemplate;
    }

    public Plan plan(UUID ownerId, List<WatchEvent> events) {
        if (ownerId == null || events == null || events.size() > MAX_EVENTS_PER_PLAN) {
            throw new IllegalArgumentException("A pass plan requires an owner and at most 200 events");
        }

        Map<UUID, Integer> nextPassByContent = new HashMap<>();
        Map<UUID, Integer> watchNumbers = new LinkedHashMap<>();
        Map<UUID, String> issues = new LinkedHashMap<>();
        Set<UUID> linkedExistingPasses = new LinkedHashSet<>();
        Map<CandidateKey, List<DateCandidate>> candidatesByScope = new HashMap<>();

        for (WatchEvent event : events) {
            if (event == null || event.itemId() == null || event.contentId() == null
                    || event.sourceNamespace() == null || event.sourceNamespace().isBlank()) {
                throw new IllegalArgumentException("Pass plan event identity is required");
            }

            int maxWatchNumber = diaryEntryRepository.findMaxWatchNumber(ownerId, event.contentId());
            int nextPass = nextPassByContent.computeIfAbsent(event.contentId(), ignored -> {
                if (maxWatchNumber == Integer.MAX_VALUE) return Integer.MAX_VALUE;
                return maxWatchNumber + 1;
            });

            if (event.assignedPass() != null) {
                if (event.assignedPass() < 1) {
                    issues.put(event.itemId(), "PASS_ASSIGNMENT_CONFLICT: The assigned watch pass must be positive.");
                    continue;
                }
                boolean existing = diaryEntryRepository.findFirstByUserIdAndContentIdAndWatchNumber(
                        ownerId, event.contentId(), event.assignedPass()).isPresent();
                if (event.linkExistingOnly() && !existing) {
                    issues.put(event.itemId(), "MATCH_REQUIRED: PASS_ASSIGNMENT_CONFLICT: This source event "
                            + "matches existing history; assign an existing pass or skip it.");
                    continue;
                }
                watchNumbers.put(event.itemId(), event.assignedPass());
                if (existing) linkedExistingPasses.add(event.itemId());
                else if (event.assignedPass() >= nextPass && event.assignedPass() < Integer.MAX_VALUE) {
                    nextPassByContent.put(event.contentId(), event.assignedPass() + 1);
                }
                continue;
            }

            CandidateKey candidateKey = new CandidateKey(event.contentId(), event.watchedDate(), event.sourceNamespace());
            List<DateCandidate> candidates = candidatesByScope.computeIfAbsent(candidateKey,
                    ignored -> findExistingCandidates(ownerId, event.contentId(), event.watchedDate(),
                            event.sourceNamespace()));
            if (candidates.stream().anyMatch(candidate -> candidate.sourceNamespaces()
                    .contains("__candidate_limit__"))) {
                issues.put(event.itemId(), "PASS_ASSIGNMENT_CONFLICT: More than 200 existing watches match this "
                        + (event.watchedDate() == null ? "content" : "date") + " scope.");
                continue;
            }
            List<Integer> matchingPasses = candidates.stream()
                    .filter(candidate -> candidate.manual()
                            || !candidate.sourceNamespaces().contains(event.sourceNamespace()))
                    .map(DateCandidate::watchNumber)
                    .distinct()
                    .sorted()
                    .toList();
            if (!matchingPasses.isEmpty()) {
                String scope = event.watchedDate() == null ? "for this undated event"
                        : "on " + event.watchedDate();
                issues.put(event.itemId(), "MATCH_REQUIRED: PASS_ASSIGNMENT_CONFLICT: Existing watch passes "
                        + matchingPasses + " " + scope + " need an explicit link or skip.");
                continue;
            }

            if (nextPass == Integer.MAX_VALUE) {
                issues.put(event.itemId(), "PASS_ASSIGNMENT_CONFLICT: No additional watch pass number is available.");
                continue;
            }
            watchNumbers.put(event.itemId(), nextPass);
            nextPassByContent.put(event.contentId(), nextPass + 1);
        }

        return new Plan(watchNumbers, issues, linkedExistingPasses);
    }

    private List<DateCandidate> findExistingCandidates(UUID ownerId, UUID contentId, LocalDate watchedDate,
                                                       String sourceNamespace) {
        List<DateCandidateRow> rows = jdbcTemplate.query("""
                SELECT diary.watch_number,
                       CASE
                         WHEN NOT EXISTS (
                             SELECT 1 FROM external_import_links linked
                             WHERE linked.user_id = diary.user_id AND linked.target_type = 'DIARY_ENTRY'
                               AND linked.target_id = diary.id AND linked.state = 'APPLIED'
                         ) THEN NULL
                         WHEN EXISTS (
                             SELECT 1 FROM external_import_links same_source
                             WHERE same_source.user_id = diary.user_id AND same_source.target_type = 'DIARY_ENTRY'
                               AND same_source.target_id = diary.id AND same_source.state = 'APPLIED'
                               AND same_source.source_namespace = ?
                         ) THEN ?
                         ELSE (
                             SELECT MIN(other_source.source_namespace) FROM external_import_links other_source
                             WHERE other_source.user_id = diary.user_id AND other_source.target_type = 'DIARY_ENTRY'
                               AND other_source.target_id = diary.id AND other_source.state = 'APPLIED'
                         )
                       END AS source_namespace
                FROM diary_entries diary
                WHERE diary.user_id = ? AND diary.content_id = ?
                  AND (CAST(? AS DATE) IS NULL OR diary.watched_date = CAST(? AS DATE))
                ORDER BY diary.watch_number, diary.id
                LIMIT 201
                """, (result, row) -> new DateCandidateRow(
                result.getInt("watch_number"), result.getString("source_namespace")), sourceNamespace,
                sourceNamespace, ownerId, contentId, watchedDate, watchedDate);
        if (rows.size() > MAX_EXISTING_CANDIDATES) {
            return List.of(new DateCandidate(0, false, Set.of("__candidate_limit__")));
        }

        Map<Integer, MutableDateCandidate> grouped = new LinkedHashMap<>();
        for (DateCandidateRow row : rows) {
            MutableDateCandidate candidate = grouped.computeIfAbsent(row.watchNumber(), ignored -> new MutableDateCandidate());
            if (row.sourceNamespace() == null) candidate.manual = true;
            else candidate.sourceNamespaces.add(row.sourceNamespace());
        }
        List<DateCandidate> candidates = new ArrayList<>(grouped.size());
        grouped.forEach((number, candidate) -> candidates.add(
                new DateCandidate(number, candidate.manual, Set.copyOf(candidate.sourceNamespaces))));
        return candidates;
    }

    public record WatchEvent(UUID itemId, UUID contentId, String sourceNamespace,
                             LocalDate watchedDate, Integer assignedPass, boolean linkExistingOnly) { }

    public record Plan(Map<UUID, Integer> watchNumbers, Map<UUID, String> issues,
                       Set<UUID> linkedExistingPasses) {
        public Plan {
            watchNumbers = Map.copyOf(watchNumbers);
            issues = Map.copyOf(issues);
            linkedExistingPasses = Set.copyOf(linkedExistingPasses);
        }
    }

    private record CandidateKey(UUID contentId, LocalDate watchedDate, String sourceNamespace) { }
    private record DateCandidateRow(int watchNumber, String sourceNamespace) { }
    private record DateCandidate(int watchNumber, boolean manual, Set<String> sourceNamespaces) { }

    private static final class MutableDateCandidate {
        private boolean manual;
        private final Set<String> sourceNamespaces = new HashSet<>();
    }
}
