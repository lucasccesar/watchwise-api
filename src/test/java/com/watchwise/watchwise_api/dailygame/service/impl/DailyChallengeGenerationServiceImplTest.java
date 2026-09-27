package com.watchwise.watchwise_api.dailygame.service.impl;

import com.watchwise.watchwise_api.common.transaction.AdvisoryLock;
import com.watchwise.watchwise_api.common.transaction.NewTransactionExecutor;
import com.watchwise.watchwise_api.dailygame.entity.DailyChallenge;
import com.watchwise.watchwise_api.dailygame.entity.DailyChallengeHint;
import com.watchwise.watchwise_api.dailygame.entity.DailyGameTargetKind;
import com.watchwise.watchwise_api.dailygame.entity.DailyGameType;
import com.watchwise.watchwise_api.dailygame.generation.DailyChallengeCandidate;
import com.watchwise.watchwise_api.dailygame.generation.DailyChallengeGenerator;
import com.watchwise.watchwise_api.dailygame.repository.DailyChallengeHintRepository;
import com.watchwise.watchwise_api.dailygame.repository.DailyChallengeRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.ArgumentCaptor;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import tools.jackson.databind.ObjectMapper;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DailyChallengeGenerationServiceImplTest {

    private static final LocalDate DATE = LocalDate.of(2026, 9, 27);
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-09-27T12:00:00Z"), ZoneOffset.UTC);

    @Mock
    private AdvisoryLock advisoryLock;

    @Mock
    private DailyChallengeRepository challengeRepository;

    @Mock
    private DailyChallengeHintRepository hintRepository;

    @Mock
    private NewTransactionExecutor newTransactionExecutor;

    @Test
    @DisplayName("[ensureGenerated] Should Lock And Persist Only Missing Modalities - When The Same Date Is Generated Repeatedly")
    void shouldLockAndPersistOnlyMissingModalitiesWhenTheSameDateIsGeneratedRepeatedly() {
        Map<DailyGameType, DailyChallengeGenerator> generators = generatorsReturningCandidates();
        Set<DailyGameType> persistedTypes = new HashSet<>();
        when(challengeRepository.existsByChallengeDateAndGameType(eq(DATE), any())).thenAnswer(invocation -> {
            DailyGameType type = invocation.getArgument(1);
            return type == DailyGameType.MOVIE_BY_POSTER || persistedTypes.contains(type);
        });
        when(challengeRepository.saveAndFlush(any(DailyChallenge.class))).thenAnswer(invocation -> {
            DailyChallenge challenge = invocation.getArgument(0);
            persistedTypes.add(challenge.getGameType());
            return challenge;
        });
        when(challengeRepository.existsByGameTypeAndAnswerKey(any(), any())).thenReturn(false);

        DailyChallengeGenerationServiceImpl service = service(generators, 2);
        service.ensureGenerated(DATE);
        service.ensureGenerated(DATE);

        verify(advisoryLock, times(2)).lock("daily-games|2026-09-27");
        verify(challengeRepository, times(7)).saveAndFlush(any(DailyChallenge.class));
    }

    @Test
    @DisplayName("[ensureGenerated] Should Leave A Modality Absent - When Every Candidate Answer Is Already Used")
    void shouldLeaveAModalityAbsentWhenEveryCandidateAnswerIsAlreadyUsed() {
        DailyChallengeGenerator exhausted = mock(DailyChallengeGenerator.class);
        when(exhausted.gameType()).thenReturn(DailyGameType.MOVIE_BY_POSTER);
        when(exhausted.generate(eq(DATE), any())).thenReturn(Optional.of(candidate(DailyGameType.MOVIE_BY_POSTER, "MOVIE:550")));
        Map<DailyGameType, DailyChallengeGenerator> generators = generatorsReturningCandidates();
        generators.put(DailyGameType.MOVIE_BY_POSTER, exhausted);
        when(challengeRepository.existsByChallengeDateAndGameType(DATE, DailyGameType.MOVIE_BY_POSTER)).thenReturn(false);
        when(challengeRepository.existsByChallengeDateAndGameType(eq(DATE), org.mockito.ArgumentMatchers.argThat(type -> type != DailyGameType.MOVIE_BY_POSTER)))
                .thenReturn(true);
        when(challengeRepository.existsByGameTypeAndAnswerKey(DailyGameType.MOVIE_BY_POSTER, "MOVIE:550")).thenReturn(true);

        service(generators, 3).ensureGenerated(DATE);

        verify(challengeRepository, never()).saveAndFlush(any(DailyChallenge.class));
        verify(exhausted, times(3)).generate(eq(DATE), any());
    }

    @Test
    @DisplayName("[ensureGenerated] Should Propagate Persistence Failure - When Isolated Persistence Fails")
    void shouldPropagatePersistenceFailureWhenIsolatedPersistenceFails() {
        Map<DailyGameType, DailyChallengeGenerator> generators = generatorsReturningCandidates();
        when(challengeRepository.existsByChallengeDateAndGameType(any(), any())).thenReturn(false);
        when(challengeRepository.existsByGameTypeAndAnswerKey(any(), any())).thenReturn(false);
        doThrow(new IllegalStateException("database unavailable")).when(challengeRepository).saveAndFlush(any(DailyChallenge.class));

        assertThatThrownBy(() -> service(generators, 1).ensureGenerated(DATE))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("database unavailable");
        verify(hintRepository, never()).saveAllAndFlush(any());
    }

    @Test
    @DisplayName("[ensureGenerated] Should Persist The Next Candidate - When The First Candidate Answer Is Already Used")
    void shouldPersistTheNextCandidateWhenTheFirstCandidateAnswerIsAlreadyUsed() {
        DailyChallengeGenerator generator = mock(DailyChallengeGenerator.class);
        DailyChallengeCandidate first = candidate(DailyGameType.MOVIE_BY_POSTER, "MOVIE:550",
                List.of(new DailyChallengeCandidate.HintSnapshot("FIRST", "first")));
        DailyChallengeCandidate second = candidate(DailyGameType.MOVIE_BY_POSTER, "MOVIE:680",
                List.of(new DailyChallengeCandidate.HintSnapshot("SECOND", "second"),
                        new DailyChallengeCandidate.HintSnapshot("THIRD", "third")));
        when(generator.gameType()).thenReturn(DailyGameType.MOVIE_BY_POSTER);
        when(generator.generate(eq(DATE), any())).thenReturn(Optional.of(first), Optional.of(second));
        Map<DailyGameType, DailyChallengeGenerator> generators = generatorsReturningCandidates();
        generators.put(DailyGameType.MOVIE_BY_POSTER, generator);
        stubOnlyMovieIsMissing();
        when(challengeRepository.existsByGameTypeAndAnswerKey(any(), any()))
                .thenAnswer(invocation -> "MOVIE:550".equals(invocation.getArgument(1)));
        when(challengeRepository.saveAndFlush(any(DailyChallenge.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        service(generators, 2).ensureGenerated(DATE);

        ArgumentCaptor<DailyChallenge> challengeCaptor = ArgumentCaptor.forClass(DailyChallenge.class);
        verify(challengeRepository).saveAndFlush(challengeCaptor.capture());
        assertThat(challengeCaptor.getValue().getAnswerKey()).isEqualTo("MOVIE:680");
        assertThat(challengeCaptor.getValue().getAnswerSnapshot()).isSameAs(second.answerSnapshot());
        assertThat(challengeCaptor.getValue().getDisplaySnapshot()).isSameAs(second.displaySnapshot());
        ArgumentCaptor<List<DailyChallengeHint>> hintsCaptor = ArgumentCaptor.forClass(List.class);
        verify(hintRepository).saveAllAndFlush(hintsCaptor.capture());
        assertThat(hintsCaptor.getValue()).extracting(DailyChallengeHint::getPosition)
                .containsExactly(1, 2);
        assertThat(hintsCaptor.getValue()).extracting(DailyChallengeHint::getHintType)
                .containsExactly("SECOND", "THIRD");
    }

    @Test
    @DisplayName("[ensureGenerated] Should Retry After An Isolated Unique Conflict - When Another Date Claims The Answer")
    void shouldRetryAfterAnIsolatedUniqueConflictWhenAnotherDateClaimsTheAnswer() {
        DailyChallengeGenerator generator = mock(DailyChallengeGenerator.class);
        DailyChallengeCandidate first = candidate(DailyGameType.MOVIE_BY_POSTER, "MOVIE:550");
        DailyChallengeCandidate second = candidate(DailyGameType.MOVIE_BY_POSTER, "MOVIE:680");
        when(generator.gameType()).thenReturn(DailyGameType.MOVIE_BY_POSTER);
        when(generator.generate(eq(DATE), any())).thenReturn(Optional.of(first), Optional.of(second));
        Map<DailyGameType, DailyChallengeGenerator> generators = generatorsReturningCandidates();
        generators.put(DailyGameType.MOVIE_BY_POSTER, generator);
        stubOnlyMovieIsMissing();
        java.util.concurrent.atomic.AtomicBoolean conflictObserved = new java.util.concurrent.atomic.AtomicBoolean();
        when(challengeRepository.existsByGameTypeAndAnswerKey(any(), any())).thenAnswer(invocation ->
                "MOVIE:550".equals(invocation.getArgument(1)) && conflictObserved.get());
        when(challengeRepository.saveAndFlush(any(DailyChallenge.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        DataIntegrityViolationException conflict = new DataIntegrityViolationException("duplicate answer key");
        DailyChallengeGenerationServiceImpl service = service(generators, 2);
        doAnswer(invocation -> {
                    conflictObserved.set(true);
                    throw conflict;
                })
                .doAnswer(invocation -> ((java.util.function.Supplier<?>) invocation.getArgument(0)).get())
                .when(newTransactionExecutor).runInNewTransaction(any());

        service.ensureGenerated(DATE);

        ArgumentCaptor<DailyChallenge> challengeCaptor = ArgumentCaptor.forClass(DailyChallenge.class);
        verify(challengeRepository).saveAndFlush(challengeCaptor.capture());
        assertThat(challengeCaptor.getValue().getAnswerKey()).isEqualTo("MOVIE:680");
        verify(newTransactionExecutor, times(2)).runInNewTransaction(any());
    }

    private DailyChallengeGenerationServiceImpl service(Map<DailyGameType, DailyChallengeGenerator> generators,
                                                        int maxCandidates) {
        lenient().when(newTransactionExecutor.runInNewTransaction(any()))
                .thenAnswer(invocation -> ((java.util.function.Supplier<?>) invocation.getArgument(0)).get());
        return new DailyChallengeGenerationServiceImpl(advisoryLock, challengeRepository, hintRepository,
                newTransactionExecutor, List.copyOf(generators.values()), maxCandidates, CLOCK);
    }

    private void stubOnlyMovieIsMissing() {
        when(challengeRepository.existsByChallengeDateAndGameType(eq(DATE), any())).thenAnswer(invocation ->
                invocation.getArgument(1) != DailyGameType.MOVIE_BY_POSTER);
    }

    private Map<DailyGameType, DailyChallengeGenerator> generatorsReturningCandidates() {
        Map<DailyGameType, DailyChallengeGenerator> generators = new EnumMap<>(DailyGameType.class);
        for (DailyGameType type : DailyGameType.values()) {
            String answerKey = type.targetKind() == DailyGameTargetKind.EPISODE
                    ? "EPISODE:1396:1:1"
                    : type.targetKind().name() + ":550";
            generators.put(type, new StubGenerator(type, Optional.of(candidate(type, answerKey))));
        }
        return generators;
    }

    private static DailyChallengeCandidate candidate(DailyGameType type, String answerKey) {
        return candidate(type, answerKey, List.of(new DailyChallengeCandidate.HintSnapshot("YEAR", "1999")));
    }

    private static DailyChallengeCandidate candidate(DailyGameType type, String answerKey,
                                                     List<DailyChallengeCandidate.HintSnapshot> hints) {
        boolean episode = type.targetKind() == DailyGameTargetKind.EPISODE;
        ObjectMapper objectMapper = new ObjectMapper();
        String targetTmdbId = episode ? null : answerKey.substring(answerKey.indexOf(':') + 1);
        return new DailyChallengeCandidate(type, type.targetKind(), targetTmdbId, episode ? "1396" : null,
                episode ? 1 : null, episode ? 1 : null, null, answerKey, "/image.jpg",
                objectMapper.createObjectNode().put("title", "Answer"), objectMapper.createObjectNode().put("imageUrl", "/image.jpg"),
                hints);
    }

    private record StubGenerator(DailyGameType gameType, Optional<DailyChallengeCandidate> result)
            implements DailyChallengeGenerator {
        @Override
        public Optional<DailyChallengeCandidate> generate(LocalDate challengeDate) {
            return result;
        }
    }
}
