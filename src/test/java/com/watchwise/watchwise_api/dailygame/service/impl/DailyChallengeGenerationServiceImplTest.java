package com.watchwise.watchwise_api.dailygame.service.impl;

import com.watchwise.watchwise_api.common.transaction.AdvisoryLock;
import com.watchwise.watchwise_api.dailygame.entity.DailyChallenge;
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
import org.mockito.junit.jupiter.MockitoExtension;
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

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
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

    @Test
    @DisplayName("Generation locks the date, skips existing modalities and persists only missing ones")
    void generationIsIdempotentPerDateAndGameType() {
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
    @DisplayName("Generation does not reuse an answer after candidate pool exhaustion")
    void generationLeavesModalityAbsentAfterExhaustion() {
        DailyChallengeGenerator exhausted = mock(DailyChallengeGenerator.class);
        when(exhausted.gameType()).thenReturn(DailyGameType.MOVIE_BY_POSTER);
        when(exhausted.generate(DATE)).thenReturn(Optional.of(candidate(DailyGameType.MOVIE_BY_POSTER, "MOVIE:550")));
        Map<DailyGameType, DailyChallengeGenerator> generators = generatorsReturningCandidates();
        generators.put(DailyGameType.MOVIE_BY_POSTER, exhausted);
        when(challengeRepository.existsByChallengeDateAndGameType(DATE, DailyGameType.MOVIE_BY_POSTER)).thenReturn(false);
        when(challengeRepository.existsByChallengeDateAndGameType(eq(DATE), org.mockito.ArgumentMatchers.argThat(type -> type != DailyGameType.MOVIE_BY_POSTER)))
                .thenReturn(true);
        when(challengeRepository.existsByGameTypeAndAnswerKey(DailyGameType.MOVIE_BY_POSTER, "MOVIE:550")).thenReturn(true);

        service(generators, 3).ensureGenerated(DATE);

        verify(challengeRepository, never()).saveAndFlush(any(DailyChallenge.class));
        verify(exhausted, times(3)).generate(DATE);
    }

    @Test
    @DisplayName("Persistence failures are not swallowed")
    void persistenceFailureRollsBackThroughTheCaller() {
        Map<DailyGameType, DailyChallengeGenerator> generators = generatorsReturningCandidates();
        when(challengeRepository.existsByChallengeDateAndGameType(any(), any())).thenReturn(false);
        when(challengeRepository.existsByGameTypeAndAnswerKey(any(), any())).thenReturn(false);
        doThrow(new IllegalStateException("database unavailable")).when(challengeRepository).saveAndFlush(any(DailyChallenge.class));

        assertThatThrownBy(() -> service(generators, 1).ensureGenerated(DATE))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("database unavailable");
        verify(hintRepository, never()).saveAllAndFlush(any());
    }

    private DailyChallengeGenerationServiceImpl service(Map<DailyGameType, DailyChallengeGenerator> generators,
                                                        int maxCandidates) {
        return new DailyChallengeGenerationServiceImpl(advisoryLock, challengeRepository, hintRepository,
                List.copyOf(generators.values()), maxCandidates, CLOCK);
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
        boolean episode = type.targetKind() == DailyGameTargetKind.EPISODE;
        ObjectMapper objectMapper = new ObjectMapper();
        return new DailyChallengeCandidate(type, type.targetKind(), episode ? null : "550", episode ? "1396" : null,
                episode ? 1 : null, episode ? 1 : null, null, answerKey, "/image.jpg",
                objectMapper.createObjectNode().put("title", "Answer"), objectMapper.createObjectNode().put("imageUrl", "/image.jpg"),
                List.of(new DailyChallengeCandidate.HintSnapshot("YEAR", "1999")));
    }

    private record StubGenerator(DailyGameType gameType, Optional<DailyChallengeCandidate> result)
            implements DailyChallengeGenerator {
        @Override
        public Optional<DailyChallengeCandidate> generate(LocalDate challengeDate) {
            return result;
        }
    }
}
