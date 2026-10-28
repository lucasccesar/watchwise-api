package com.watchwise.watchwise_api.pick.controller;

import com.watchwise.watchwise_api.common.transaction.UserPairLockIdentity;
import com.watchwise.watchwise_api.common.tmdb.*;
import com.watchwise.watchwise_api.content.entity.ContentType;
import com.watchwise.watchwise_api.content.repository.ContentRepository;
import com.watchwise.watchwise_api.pick.dto.PickCreationDTO;
import com.watchwise.watchwise_api.pick.dto.PickSelectionCreationDTO;
import com.watchwise.watchwise_api.pick.dto.PickTargetDTO;
import com.watchwise.watchwise_api.pick.entity.PickVisibility;
import com.watchwise.watchwise_api.pick.support.PicksDomainIntegrationSupport;
import com.watchwise.watchwise_api.userblock.service.UserBlockService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpMethod;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.CountDownLatch;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.times;
import static org.mockito.ArgumentMatchers.anyString;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class PickBlockMutationIntegrationTest extends PicksDomainIntegrationSupport {

    @Autowired private UserBlockService blocks;
    @Autowired private ContentRepository contents;

    @ParameterizedTest
    @EnumSource(Mutation.class)
    void shouldPrepareTmdbOutsideTransactionAndAllowBlockBeforeTheFinalWrite(Mutation mutation) throws Exception {
        var template = template();
        UUID pickId = existingPickFor(mutation, template);
        String identity = UserPairLockIdentity.of(owner.getId(), other.getId());
        var preparing = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var paused = new AtomicBoolean();
        var transactionActive = new AtomicBoolean();
        when(tmdb.getPersonDetails(anyString())).thenAnswer(call -> {
            if (paused.compareAndSet(false, true)) {
                transactionActive.set(TransactionSynchronizationManager.isActualTransactionActive());
                preparing.countDown();
                if (!release.await(10, TimeUnit.SECONDS)) throw new AssertionError("Preparation was not released");
            }
            return new TmdbLookupResult.Found<>(new TmdbPersonDetails(call.getArgument(0)));
        });
        var executor = Executors.newFixedThreadPool(2);
        try {
            var mutationRequest = executor.submit(() -> mvc.perform(mutationRequest(mutation, template, pickId)).andReturn());
            assertThat(preparing.await(5, TimeUnit.SECONDS)).isTrue();
            assertThat(transactionActive.get()).as("TMDB preparation must not hold a mutation transaction").isFalse();
            var block = executor.submit(() -> mvc.perform(authenticated(owner, HttpMethod.POST,
                    "/users/me/blocked/" + other.getId(), "")).andReturn());
            await().atMost(Duration.ofSeconds(5)).until(() -> block.isDone() || pairLockWaiters(identity) > 0);
            assertThat(block.isDone()).as("A TMDB call must not retain the user-pair lock").isTrue();
            status().isNoContent().match(block.get(10, TimeUnit.SECONDS));
            release.countDown();
            status().isNotFound().match(mutationRequest.get(10, TimeUnit.SECONDS));
            assertMutationNotApplied(mutation, pickId);
        } finally {
            release.countDown();
            executor.shutdownNow();
            executor.awaitTermination(10, TimeUnit.SECONDS);
        }
    }

    @ParameterizedTest
    @EnumSource(Mutation.class)
    void shouldKeepTheFinalPairLockUntilMutationCompletesAndMakePostBlockWait(Mutation mutation) throws Exception {
        var template = template();
        UUID pickId = existingPickFor(mutation, template);
        String identity = UserPairLockIdentity.of(owner.getId(), other.getId());
        var mutationRequest = new AtomicReference<Future<MvcResult>>();
        var blockRequest = new AtomicReference<Future<MvcResult>>();
        var executor = Executors.newFixedThreadPool(2);
        try {
            new TransactionTemplate(transactions).executeWithoutResult(transaction -> {
                int blockingPid = jdbc.queryForObject("SELECT pg_backend_pid()", Integer.class);
                if (mutation == Mutation.VISIBILITY) {
                    jdbc.queryForObject("SELECT id FROM picks WHERE id = ? FOR UPDATE", UUID.class, pickId);
                } else {
                    jdbc.queryForObject("SELECT id FROM picks_templates WHERE id = ? FOR UPDATE", UUID.class, template.id());
                }
                mutationRequest.set(executor.submit(() -> mvc.perform(mutationRequest(mutation, template, pickId)).andReturn()));
                await().atMost(Duration.ofSeconds(5)).until(() -> pairLockHolderBlockedBy(identity, blockingPid) > 0
                        || mutationRequest.get().isDone());
                assertThat(mutationRequest.get().isDone()).isFalse();
                assertThat(pairLockHolderBlockedBy(identity, blockingPid)).isPositive();
                blockRequest.set(executor.submit(() -> mvc.perform(authenticated(owner, HttpMethod.POST,
                        "/users/me/blocked/" + other.getId(), "")).andReturn()));
                await().atMost(Duration.ofSeconds(5)).until(() -> pairLockWaiters(identity) > 0 || blockRequest.get().isDone());
                assertThat(blockRequest.get().isDone()).isFalse();
                assertThat(pairLockWaiters(identity)).isPositive();
            });
            assertThat(mutationRequest.get().get(10, TimeUnit.SECONDS).getResponse().getStatus())
                    .isEqualTo(mutation == Mutation.CREATE ? 201 : 200);
            status().isNoContent().match(blockRequest.get().get(10, TimeUnit.SECONDS));
        } finally {
            executor.shutdownNow();
            executor.awaitTermination(10, TimeUnit.SECONDS);
        }
    }

    @ParameterizedTest
    @EnumSource(Mutation.class)
    void shouldObserveUnblockCommittedWhileMutationWaitsForPairLock(Mutation mutation) throws Exception {
        var template = template();
        UUID pickId = existingPickFor(mutation, template);
        blocks.block(owner.getId(), other.getId());
        String identity = UserPairLockIdentity.of(owner.getId(), other.getId());
        var pending = new AtomicReference<Future<MvcResult>>();
        var executor = Executors.newSingleThreadExecutor();
        try {
            new TransactionTemplate(transactions).executeWithoutResult(transaction -> {
                jdbc.queryForObject("SELECT pg_advisory_xact_lock(hashtext(?))", String.class, identity);
                blocks.unblock(owner.getId(), other.getId());
                pending.set(executor.submit(() -> mvc.perform(mutationRequest(mutation, template, pickId)).andReturn()));
                await().atMost(Duration.ofSeconds(5)).until(() -> pairLockWaiters(identity) > 0 || pending.get().isDone());
                assertThat(pending.get().isDone()).isFalse();
                assertThat(pairLockWaiters(identity)).isPositive();
            });
            assertThat(pending.get().get(10, TimeUnit.SECONDS).getResponse().getStatus())
                    .isEqualTo(mutation == Mutation.CREATE ? 201 : 200);
        } finally {
            executor.shutdownNow();
            executor.awaitTermination(10, TimeUnit.SECONDS);
        }
    }

    @Test
    void shouldPreserveMovieMetadataWhilePreparingWithoutWritesOrTransaction() throws Exception {
        var template = template();
        var transactionObserved = new AtomicBoolean();
        var writeObserved = new AtomicBoolean();
        var details = new TmdbMovieFullDetails("550", "Movie", null, null, null, null, "2025-06-01", 137,
                List.of(new TmdbGenre(1, "Drama")), List.of(new TmdbProductionCountry("br", "Brazil")),
                null, null, null, null, null, null, null);
        when(tmdb.getMovieFullDetails(anyString(), anyString())).thenAnswer(call -> {
            transactionObserved.compareAndSet(false, TransactionSynchronizationManager.isActualTransactionActive());
            writeObserved.compareAndSet(false, contents.count() != 0);
            return new TmdbLookupResult.Found<>(details);
        });

        mvc.perform(authenticated(other, HttpMethod.POST, "/picks-templates/" + template.id() + "/picks", """
                {"selections":[{"categoryId":"%s","target":{"content":{"type":"MOVIE","tmdbId":"550"}}}]}
                """.formatted(template.movie()))).andExpect(status().isCreated());

        assertThat(transactionObserved.get()).as("TMDB preparation must not hold a mutation transaction").isFalse();
        assertThat(writeObserved.get()).as("External preparation cannot create Content references").isFalse();

        var content = contents.findByTmdbIdAndType("550", ContentType.MOVIE).orElseThrow();
        assertThat(content.getRuntimeMinutes()).isEqualTo(137);
        assertThat(content.getGenres()).containsExactly("Drama");
        assertThat(content.getReleaseYear()).isEqualTo(2025);
        assertThat(content.getCountries()).containsExactly("BR");
    }

    @ParameterizedTest
    @CsvSource({"VISIBILITY, 1", "VISIBILITY, 4", "SELECTION, 1", "SELECTION, 4"})
    void shouldRetryChangedSelectionSnapshotsAndKeepAnExhaustedMutationAtomic(Mutation mutation, int changes) throws Exception {
        var template = template();
        UUID pickId;
        if (mutation == Mutation.SELECTION) {
            jdbc.update("UPDATE picks_template_categories SET allowed_type = 'PERSON' WHERE id = ?", template.movie());
            pickId = pickService.createPick(other.getId(), template.id(), new PickCreationDTO(PickVisibility.PUBLIC,
                    List.of(new PickSelectionCreationDTO(template.person(), new PickTargetDTO(null, "42", null)),
                            new PickSelectionCreationDTO(template.movie(), new PickTargetDTO(null, "99", null))))).id();
        } else {
            pickId = existingPickFor(Mutation.VISIBILITY, template);
        }
        var replacing = new AtomicBoolean();
        var remainingChanges = new AtomicInteger(changes);
        var preparations = new AtomicInteger();
        when(tmdb.getPersonDetails(anyString())).thenAnswer(call -> {
            boolean retainedResponseTarget = mutation != Mutation.SELECTION || "99".equals(call.getArgument(0));
            if (retainedResponseTarget && replacing.compareAndSet(false, true)) {
                try {
                    preparations.incrementAndGet();
                    if (remainingChanges.getAndDecrement() > 0) {
                        String current = jdbc.queryForObject("SELECT person_tmdb_id FROM pick_selections WHERE pick_id = ? AND category_id = ?",
                                String.class, pickId, template.person());
                        String replacement = "42".equals(current) ? "44" : "42";
                        selectionService.upsertSelection(other.getId(), pickId, template.person(),
                                new PickTargetDTO(null, replacement, null));
                    }
                } finally {
                    replacing.set(false);
                }
            }
            return new TmdbLookupResult.Found<>(new TmdbPersonDetails(call.getArgument(0)));
        });

        MvcResult result = mvc.perform(mutationRequest(mutation, template, pickId)).andReturn();

        if (changes == 1) {
            status().isOk().match(result);
            jsonPath("$.selections[?(@.categoryId == '%s')].target.personTmdbId".formatted(template.person()))
                    .value(org.hamcrest.Matchers.contains(mutation == Mutation.SELECTION ? "43" : "44")).match(result);
            jsonPath("$.selections[?(@.categoryId == '%s')].isValid".formatted(template.person()))
                    .value(org.hamcrest.Matchers.contains(true)).match(result);
            assertThat(preparations.get()).isEqualTo(2);
            assertThat(picks.findById(pickId).orElseThrow().getVisibility())
                    .isEqualTo(mutation == Mutation.VISIBILITY ? PickVisibility.PRIVATE : PickVisibility.PUBLIC);
        } else {
            status().isConflict().match(result);
            jsonPath("$.status").value(409).match(result);
            assertThat(preparations.get()).isEqualTo(4);
            assertThat(picks.findById(pickId).orElseThrow().getVisibility()).isEqualTo(PickVisibility.PUBLIC);
            assertThat(jdbc.queryForObject("SELECT person_tmdb_id FROM pick_selections WHERE pick_id = ? AND category_id = ?",
                            String.class, pickId, template.person()))
                    .isEqualTo("42");
        }
    }

    @Test
    void shouldKeepInvalidSelectionsEditableWithoutConsultingTmdb() throws Exception {
        var template = template();
        UUID pickId = existingPickFor(Mutation.VISIBILITY, template);
        jdbc.update("UPDATE picks_template_categories SET allowed_type = 'MOVIE' WHERE id = ?", template.person());
        when(tmdb.getPersonDetails(anyString())).thenReturn(new TmdbLookupResult.Unavailable<>());
        clearInvocations(tmdb);

        mvc.perform(mutationRequest(Mutation.VISIBILITY, template, pickId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.selections[0].isValid").value(false));

        verifyNoInteractions(tmdb);
    }

    @Test
    void shouldReplaceAnUnavailableOldSelectionWithAHealthyTarget() throws Exception {
        var template = template();
        UUID pickId = existingPickFor(Mutation.SELECTION, template);
        when(tmdb.getPersonDetails("42")).thenReturn(new TmdbLookupResult.Unavailable<>());
        clearInvocations(tmdb);

        mvc.perform(mutationRequest(Mutation.SELECTION, template, pickId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.selections[0].target.personTmdbId").value("43"))
                .andExpect(jsonPath("$.selections[0].isValid").value(true));

        verify(tmdb, org.mockito.Mockito.never()).getPersonDetails("42");
        assertThat(jdbc.queryForObject("SELECT person_tmdb_id FROM pick_selections WHERE pick_id = ?", String.class, pickId))
                .isEqualTo("43");
    }

    @Test
    void shouldReprepareWhenCategoryChangesToRequirePreviouslySkippedFacts() throws Exception {
        var template = template();
        UUID pickId = pickService.createPick(other.getId(), template.id(), new PickCreationDTO(PickVisibility.PUBLIC,
                List.of(new PickSelectionCreationDTO(template.person(), new PickTargetDTO(null, "42", null)),
                        new PickSelectionCreationDTO(template.movie(), movieTarget("550"))))).id();
        jdbc.update("UPDATE picks_template_categories SET allowed_type = 'PERSON' WHERE id = ?", template.movie());
        var preparing = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var paused = new AtomicBoolean();
        when(tmdb.getPersonDetails(anyString())).thenAnswer(call -> {
            if (paused.compareAndSet(false, true)) {
                preparing.countDown();
                if (!release.await(10, TimeUnit.SECONDS)) throw new AssertionError("Preparation was not released");
            }
            return new TmdbLookupResult.Found<>(new TmdbPersonDetails(call.getArgument(0)));
        });
        clearInvocations(tmdb);
        var executor = Executors.newSingleThreadExecutor();
        try {
            var patch = executor.submit(() -> mvc.perform(mutationRequest(Mutation.VISIBILITY, template, pickId)).andReturn());
            assertThat(preparing.await(5, TimeUnit.SECONDS)).isTrue();
            jdbc.update("UPDATE picks_template_categories SET allowed_type = 'MOVIE' WHERE id = ?", template.movie());
            release.countDown();
            MvcResult result = patch.get(10, TimeUnit.SECONDS);
            status().isOk().match(result);
            jsonPath("$.selections[?(@.categoryId == '%s')].isValid".formatted(template.movie()))
                    .value(org.hamcrest.Matchers.contains(true)).match(result);
            verify(tmdb, times(2)).getPersonDetails("42");
            verify(tmdb).getMovieFullDetails("550", TmdbClient.LANGUAGE_INDEPENDENT_LOOKUP_LANGUAGE);
        } finally {
            release.countDown();
            executor.shutdownNow();
            executor.awaitTermination(10, TimeUnit.SECONDS);
        }
    }

    @Test
    void shouldRevalidateCurrentEligibilityAfterTemplateChangesDuringPreparation() throws Exception {
        var template = template();
        var preparing = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var paused = new AtomicBoolean();
        when(tmdb.getMovieFullDetails(anyString(), anyString())).thenAnswer(call -> {
            if (paused.compareAndSet(false, true)) {
                preparing.countDown();
                if (!release.await(10, TimeUnit.SECONDS)) throw new AssertionError("Preparation was not released");
            }
            return new TmdbLookupResult.Found<>(movie(call.getArgument(0), "2025-06-01"));
        });
        var executor = Executors.newSingleThreadExecutor();
        try {
            var creation = executor.submit(() -> mvc.perform(authenticated(other, HttpMethod.POST,
                    "/picks-templates/" + template.id() + "/picks", """
                    {"selections":[{"categoryId":"%s","target":{"content":{"type":"MOVIE","tmdbId":"550"}}}]}
                    """.formatted(template.movie()))).andReturn());
            assertThat(preparing.await(5, TimeUnit.SECONDS)).isTrue();
            mvc.perform(authenticated(owner, HttpMethod.PATCH, "/picks-templates/" + template.id(),
                    "{\"eligibilityStartDate\":\"2024-01-01\",\"eligibilityEndDate\":\"2024-12-31\"}"))
                    .andExpect(status().isOk());
            release.countDown();
            status().isBadRequest().match(creation.get(10, TimeUnit.SECONDS));
            assertThat(picks.count()).isZero();
            assertThat(selections.count()).isZero();
        } finally {
            release.countDown();
            executor.shutdownNow();
            executor.awaitTermination(10, TimeUnit.SECONDS);
        }
    }

    @Test
    void shouldPreserveEpisodeRuntimeAndParentSeriesMetadataWithoutWritingDuringPreparation() throws Exception {
        var template = template();
        var transactionObserved = new AtomicBoolean();
        var writeObserved = new AtomicBoolean();
        jdbc.update("UPDATE picks_template_categories SET allowed_type = 'EPISODE' WHERE id = ?", template.movie());
        var episode = new TmdbEpisodeFullDetails(1231, "Episode", null, "2025-06-01", 1, 1, 43, null, List.of());
        var series = new TmdbTvFullDetails("123", "Series", null, null, null, null, "2020-01-01", null,
                List.of(new TmdbGenre(1, "Drama")), List.of(new TmdbProductionCountry("us", "United States")),
                null, null, null, null, null, null, null, null, null, null, null);
        when(tmdb.getEpisodeFullDetails(anyString(), org.mockito.ArgumentMatchers.anyInt(),
                org.mockito.ArgumentMatchers.anyInt(), anyString())).thenAnswer(call -> {
            transactionObserved.compareAndSet(false, TransactionSynchronizationManager.isActualTransactionActive());
            writeObserved.compareAndSet(false, contents.count() != 0);
            return new TmdbLookupResult.Found<>(episode);
        });
        when(tmdb.getTvFullDetails(anyString(), anyString())).thenAnswer(call -> {
            transactionObserved.compareAndSet(false, TransactionSynchronizationManager.isActualTransactionActive());
            writeObserved.compareAndSet(false, contents.count() != 0);
            return new TmdbLookupResult.Found<>(series);
        });

        mvc.perform(authenticated(other, HttpMethod.POST, "/picks-templates/" + template.id() + "/picks", """
                {"selections":[{"categoryId":"%s","target":{"content":{"type":"EPISODE","seriesTmdbId":"123","seasonNumber":1,"episodeNumber":1}}}]}
                """.formatted(template.movie()))).andExpect(status().isCreated());

        assertThat(transactionObserved.get()).as("TMDB preparation must not hold a mutation transaction").isFalse();
        assertThat(writeObserved.get()).as("External preparation cannot create Content references").isFalse();

        var content = contents.findBySeriesTmdbIdAndSeasonNumberAndEpisodeNumberAndType("123", 1, 1, ContentType.EPISODE).orElseThrow();
        assertThat(content.getRuntimeMinutes()).isEqualTo(43);
        var parent = contents.findByTmdbIdAndType("123", ContentType.SERIES).orElseThrow();
        assertThat(parent.getGenres()).containsExactly("Drama");
        assertThat(parent.getReleaseYear()).isEqualTo(2020);
        assertThat(parent.getCountries()).containsExactly("US");
    }

    @ParameterizedTest
    @EnumSource(Mutation.class)
    void shouldRejectMutationWhenTemplateCreatorBlockedActor(Mutation mutation) throws Exception {
        var template = template();
        UUID pickId = existingPickFor(mutation, template);
        blocks.block(owner.getId(), other.getId());
        clearInvocations(tmdb);

        mvc.perform(mutationRequest(mutation, template, pickId))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404));

        assertMutationNotApplied(mutation, pickId);
        verifyNoInteractions(tmdb);
    }

    @ParameterizedTest
    @EnumSource(Mutation.class)
    void shouldRecheckBlockAfterWaitingForTheSharedPairLock(Mutation mutation) throws Exception {
        var template = template();
        UUID pickId = existingPickFor(mutation, template);
        String identity = UserPairLockIdentity.of(owner.getId(), other.getId());
        var request = new AtomicReference<Future<MvcResult>>();
        var executor = Executors.newSingleThreadExecutor();
        clearInvocations(tmdb);
        try {
            new TransactionTemplate(transactions).executeWithoutResult(transaction -> {
                jdbc.queryForObject("SELECT pg_advisory_xact_lock(hashtext(?))", String.class, identity);
                request.set(executor.submit(() -> mvc.perform(mutationRequest(mutation, template, pickId)).andReturn()));
                await().atMost(Duration.ofSeconds(5)).until(() -> pairLockWaiters(identity) > 0 || request.get().isDone());
                assertThat(request.get().isDone()).as("The mutation must wait for the shared user-pair lock").isFalse();
                assertThat(pairLockWaiters(identity)).isPositive();
                blocks.block(owner.getId(), other.getId());
            });

            MvcResult result = request.get().get(10, TimeUnit.SECONDS);
            status().isNotFound().match(result);
            jsonPath("$.status").value(404).match(result);
            assertMutationNotApplied(mutation, pickId);
            verifyNoInteractions(tmdb);
        } finally {
            executor.shutdownNow();
        }
    }

    @ParameterizedTest
    @EnumSource(Mutation.class)
    void shouldRejectMutationWhenActorBlockedTemplateCreator(Mutation mutation) throws Exception {
        var template = template();
        UUID pickId = existingPickFor(mutation, template);
        blocks.block(other.getId(), owner.getId());
        clearInvocations(tmdb);

        mvc.perform(mutationRequest(mutation, template, pickId))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404));

        assertMutationNotApplied(mutation, pickId);
        verifyNoInteractions(tmdb);
    }

    @Test
    void shouldAllowMutationsOnOfficialTemplateWithoutCreator() throws Exception {
        var template = template();
        jdbc.update("UPDATE picks_templates SET creator_id = NULL, origin = 'OFFICIAL' WHERE id = ?", template.id());
        blocks.block(owner.getId(), other.getId());

        MvcResult created = mvc.perform(mutationRequest(Mutation.CREATE, template, null))
                .andExpect(status().isCreated()).andReturn();
        UUID pickId = id(created, "$.id");
        mvc.perform(mutationRequest(Mutation.SELECTION, template, pickId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.selections[0].target.personTmdbId").value("43"));
        mvc.perform(mutationRequest(Mutation.VISIBILITY, template, pickId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.visibility").value("PRIVATE"));

        assertThat(picks.findById(pickId).orElseThrow().getVisibility()).isEqualTo(PickVisibility.PRIVATE);
    }

    @Test
    void shouldAllowDeletingOwnPickAfterTemplateCreatorBlockedActor() throws Exception {
        var template = template();
        UUID pickId = existingPickFor(Mutation.VISIBILITY, template);
        blocks.block(owner.getId(), other.getId());

        mvc.perform(authenticated(other, HttpMethod.DELETE, "/picks/" + pickId, ""))
                .andExpect(status().isNoContent());

        assertThat(picks.existsById(pickId)).isFalse();
        assertThat(selections.countByPickId(pickId)).isZero();
    }

    private UUID existingPickFor(Mutation mutation, TemplateFixture template) {
        if (mutation == Mutation.CREATE) return null;
        return pickService.createPick(other.getId(), template.id(), new PickCreationDTO(PickVisibility.PUBLIC,
                List.of(new PickSelectionCreationDTO(template.person(), new PickTargetDTO(null, "42", null))))).id();
    }

    private MockHttpServletRequestBuilder mutationRequest(Mutation mutation, TemplateFixture template, UUID pickId) {
        return switch (mutation) {
            case CREATE -> authenticated(other, HttpMethod.POST, "/picks-templates/" + template.id() + "/picks",
                    """
                    {"selections":[{"categoryId":"%s","target":{"personTmdbId":"42"}}]}
                    """.formatted(template.person()));
            case VISIBILITY -> authenticated(other, HttpMethod.PATCH, "/picks/" + pickId, "{\"visibility\":\"PRIVATE\"}");
            case SELECTION -> authenticated(other, HttpMethod.PUT,
                    "/picks/" + pickId + "/categories/" + template.person() + "/selection", "{\"personTmdbId\":\"43\"}");
        };
    }

    private void assertMutationNotApplied(Mutation mutation, UUID pickId) {
        if (mutation == Mutation.CREATE) {
            assertThat(picks.count()).isZero();
            assertThat(selections.count()).isZero();
        } else {
            assertThat(picks.findById(pickId).orElseThrow().getVisibility()).isEqualTo(PickVisibility.PUBLIC);
            assertThat(jdbc.queryForObject("SELECT person_tmdb_id FROM pick_selections WHERE pick_id = ?",
                    String.class, pickId)).isEqualTo("42");
        }
    }

    private int pairLockWaiters(String identity) {
        return jdbc.queryForObject("""
                SELECT count(*) FROM pg_locks
                WHERE locktype = 'advisory' AND NOT granted AND objsubid = 1
                  AND classid = ((hashtext(?)::bigint >> 32) & 4294967295)::oid
                  AND objid = (hashtext(?)::bigint & 4294967295)::oid
                """, Integer.class, identity, identity);
    }

    private int pairLockHolderBlockedBy(String identity, int blockingPid) {
        return jdbc.queryForObject("""
                SELECT count(*) FROM pg_locks
                WHERE locktype = 'advisory' AND granted AND objsubid = 1
                  AND classid = ((hashtext(?)::bigint >> 32) & 4294967295)::oid
                  AND objid = (hashtext(?)::bigint & 4294967295)::oid
                  AND ? = ANY(pg_blocking_pids(pid))
                """, Integer.class, identity, identity, blockingPid);
    }

    private enum Mutation { CREATE, VISIBILITY, SELECTION }
}
