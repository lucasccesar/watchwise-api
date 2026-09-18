package com.watchwise.watchwise_api.pick.controller;

import com.watchwise.watchwise_api.common.exception.ConflictException;
import com.watchwise.watchwise_api.common.security.CookieUtil;
import com.watchwise.watchwise_api.common.tmdb.TmdbLookupResult;
import com.watchwise.watchwise_api.pick.entity.PickVisibility;
import com.watchwise.watchwise_api.pick.support.PicksDomainIntegrationSupport;
import com.watchwise.watchwise_api.pickstemplate.dto.PicksTemplatePatchDTO;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;

import java.time.Duration;
import java.time.LocalDate;
import java.util.List;
import java.util.concurrent.*;

import static org.assertj.core.api.Assertions.*;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class PickDomainIntegrationTest extends PicksDomainIntegrationSupport {

    @Test
    @DisplayName("[createPick] Should Persist Valid Pick And Roll Back Invalid Pick - When Requests Reach Real Services")
    void shouldPersistValidPickAndRollBackInvalidPick() throws Exception {
        var template = template();
        String path = "/picks-templates/" + template.id() + "/picks";
        mvc.perform(authenticated(owner, HttpMethod.POST, path, """
                {"selections":[{"categoryId":"%s","target":{"personTmdbId":"42"}}]}
                """.formatted(template.person())))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.progress").value("PARTIAL"))
                .andExpect(jsonPath("$.selections[0].isValid").value(true));
        when(tmdb.getMovieFullDetails(eq("999"), anyString())).thenReturn(new TmdbLookupResult.NotFound<>());
        mvc.perform(authenticated(owner, HttpMethod.POST, path, """
                {"selections":[{"categoryId":"%s","target":{"personTmdbId":"42"}},
                  {"categoryId":"%s","target":{"content":{"type":"MOVIE","tmdbId":"999"}}}]}
                """.formatted(template.person(), template.movie())))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.status").value(404));

        assertThat(picks.count()).isEqualTo(1);
        assertThat(selections.count()).isEqualTo(1);
    }

    @Test
    @DisplayName("[createPick] Should Roll Back Pick And Selections - When Response Revalidation Cannot Reach TMDB")
    void shouldRollBackPickAndSelectionsWhenResponseRevalidationIsUnavailable() throws Exception {
        var template = template();
        when(tmdb.getMovieFullDetails(eq("550"), anyString())).thenReturn(
                new TmdbLookupResult.Found<>(movie("550", "2025-06-01")),
                new TmdbLookupResult.Found<>(movie("550", "2025-06-01")),
                new TmdbLookupResult.Unavailable<>());

        mvc.perform(authenticated(owner, HttpMethod.POST, "/picks-templates/" + template.id() + "/picks", """
                {"selections":[{"categoryId":"%s","target":{"content":{"type":"MOVIE","tmdbId":"550"}}}]}
                """.formatted(template.movie())))
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.status").value(502));

        assertThat(picks.count()).isZero();
        assertThat(selections.count()).isZero();
    }

    @Test
    @DisplayName("[upsertSelection] Should Persist Replacement And Keep Cardinality - When A Category Already Has A Selection")
    void shouldPersistReplacementAndKeepCardinalityWhenCategoryAlreadyHasSelection() throws Exception {
        var template = template();
        var pick = personPick(template, PickVisibility.PUBLIC);
        selectionService.upsertSelection(owner.getId(), pick.id(), template.movie(), movieTarget("550"));

        selectionService.upsertSelection(owner.getId(), pick.id(), template.movie(), movieTarget("551"));

        var persistedSelections = selections.findByPickIdIn(List.of(pick.id()));
        assertThat(persistedSelections).hasSize(2);
        assertThat(selectedMovieTmdbId(pick.id(), template.movie())).isEqualTo("551");
    }

    @Test
    @DisplayName("[upsertSelection] Should Restore Existing Target - When Response Revalidation Fails After Replacement")
    void shouldRestoreExistingTargetWhenResponseRevalidationFailsAfterReplacement() throws Exception {
        var template = template();
        var pick = personPick(template, PickVisibility.PUBLIC);
        selectionService.upsertSelection(owner.getId(), pick.id(), template.movie(), movieTarget("550"));
        when(tmdb.getMovieFullDetails(eq("551"), anyString())).thenReturn(
                new TmdbLookupResult.Found<>(movie("551", "2025-06-01")),
                new TmdbLookupResult.Found<>(movie("551", "2025-06-01")),
                new TmdbLookupResult.Unavailable<>());

        mvc.perform(authenticated(owner, HttpMethod.PUT,
                "/picks/" + pick.id() + "/categories/" + template.movie() + "/selection", """
                {"content":{"type":"MOVIE","tmdbId":"551"}}
                """))
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.status").value(502));

        var persistedSelections = selections.findByPickIdIn(List.of(pick.id()));
        assertThat(persistedSelections).hasSize(2);
        assertThat(selectedMovieTmdbId(pick.id(), template.movie())).isEqualTo("550");
    }

    @Test
    @DisplayName("[getPick] Should Apply Pick Visibility And Accepted Followers - When Reading An Individual Pick And The Template Collection")
    void shouldApplyPickVisibilityAndAcceptedFollowers() throws Exception {
        owner.setIsProfilePublic(false);
        users.saveAndFlush(owner);
        var template = template();
        var publicPick = personPick(template, PickVisibility.PUBLIC);
        var followersPick = personPick(template, PickVisibility.FOLLOWERS);
        var privatePick = personPick(template, PickVisibility.PRIVATE);
        mvc.perform(authenticated(other, HttpMethod.GET, "/picks/" + publicPick.id(), "")).andExpect(status().isOk());
        mvc.perform(authenticated(other, HttpMethod.GET, "/picks/" + followersPick.id(), "")).andExpect(status().isForbidden());
        jdbc.update("INSERT INTO followers (id, follower_id, followed_id, status, created_at) VALUES (?, ?, ?, 'PENDING', now())",
                java.util.UUID.randomUUID(), other.getId(), owner.getId());
        mvc.perform(authenticated(other, HttpMethod.GET, "/picks/" + followersPick.id(), "")).andExpect(status().isForbidden());
        jdbc.update("UPDATE followers SET status = 'ACCEPTED' WHERE follower_id = ?", other.getId());
        mvc.perform(authenticated(other, HttpMethod.GET, "/picks/" + followersPick.id(), "")).andExpect(status().isOk());
        mvc.perform(authenticated(other, HttpMethod.GET, "/picks/" + privatePick.id(), "")).andExpect(status().isForbidden());
        mvc.perform(authenticated(owner, HttpMethod.GET, "/picks-templates/" + template.id() + "/picks", ""))
                .andExpect(status().isOk()).andExpect(jsonPath("$.totalElements").value(3));
        mvc.perform(authenticated(other, HttpMethod.GET, "/picks-templates/" + template.id() + "/picks", ""))
                .andExpect(status().isOk()).andExpect(jsonPath("$.totalElements").value(2))
                .andExpect(jsonPath("$.content[*].id").value(
                        org.hamcrest.Matchers.containsInAnyOrder(publicPick.id().toString(), followersPick.id().toString())));
        mvc.perform(authenticated(other, HttpMethod.GET, "/users/" + owner.getId() + "/picks?templateId=" + template.id(), ""))
                .andExpect(status().isOk()).andExpect(jsonPath("$.totalElements").value(2));
        mvc.perform(authenticated(owner, HttpMethod.GET, "/picks-templates/" + template.id() + "/my-picks", ""))
                .andExpect(status().isOk()).andExpect(jsonPath("$.totalElements").value(3));
        mvc.perform(authenticated(other, HttpMethod.DELETE, "/picks/" + privatePick.id(), ""))
                .andExpect(status().isNotFound());
        assertThat(picks.existsById(privatePick.id())).isTrue();
    }

    @Test
    @DisplayName("[deleteSelection] Should Keep One Selection - When Two Transactions Delete Concurrently")
    void shouldKeepOneSelectionWhenTwoTransactionsDeleteConcurrently() throws Exception {
        var template = template();
        var pick = personPick(template, PickVisibility.PUBLIC);
        selectionService.upsertSelection(owner.getId(), pick.id(), template.movie(), movieTarget("550"));
        var start = new CyclicBarrier(2);
        var executor = Executors.newFixedThreadPool(2);
        try {
            Callable<String> first = () -> deleteAfterBarrier(start, pick.id(), template.person());
            Callable<String> second = () -> deleteAfterBarrier(start, pick.id(), template.movie());
            var a = executor.submit(first);
            var b = executor.submit(second);
            assertThat(List.of(a.get(10, TimeUnit.SECONDS), b.get(10, TimeUnit.SECONDS)))
                    .containsExactlyInAnyOrder("deleted", "conflict");
            assertThat(selections.countByPickId(pick.id())).isEqualTo(1);
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    @DisplayName("[upsertSelection] Should Serialize Period Change - When First Content Selection Is Being Validated")
    void shouldSerializePeriodChangeWithFirstContentSelection() throws Exception {
        var template = template();
        var pick = personPick(template, PickVisibility.PUBLIC);
        var validating = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        when(tmdb.getMovieFullDetails(eq("550"), anyString())).thenAnswer(call -> {
            validating.countDown();
            if (!release.await(10, TimeUnit.SECONDS)) {
                throw new AssertionError("Validation was not released");
            }
            return new TmdbLookupResult.Found<>(movie("550", "2025-06-01"));
        });
        var executor = Executors.newFixedThreadPool(2);
        try {
            var selection = executor.submit(() ->
                    selectionService.upsertSelection(owner.getId(), pick.id(), template.movie(), movieTarget("550")));
            assertThat(validating.await(5, TimeUnit.SECONDS)).isTrue();
            var patch = executor.submit(() -> templateService.updateTemplate(owner.getId(), template.id(),
                    new PicksTemplatePatchDTO(null, null, null, null,
                            LocalDate.of(2026, 1, 1), LocalDate.of(2026, 12, 31), false)));
            await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> assertThat(jdbc.queryForObject(
                    "SELECT count(*) FROM pg_stat_activity WHERE wait_event_type = 'Lock' AND query LIKE '%picks_templates%'",
                    Integer.class)).isPositive());
            assertThat(patch.isDone()).isFalse();
            release.countDown();
            assertThat(selection.get(10, TimeUnit.SECONDS).selections()).hasSize(2);
            assertThatThrownBy(() -> patch.get(10, TimeUnit.SECONDS))
                    .isInstanceOf(ExecutionException.class).hasCauseInstanceOf(ConflictException.class);
            assertThat(templates.findById(template.id()).orElseThrow().getEligibilityStartDate()).isNull();
        } finally {
            release.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    @DisplayName("[securityFilterChain] Should Require Authentication And Mutation CSRF - When Accessing Every Picks Route")
    void shouldRequireAuthenticationAndMutationCsrfForEveryRoute() throws Exception {
        String id = java.util.UUID.randomUUID().toString();
        String template = "/picks-templates/" + id;
        String category = template + "/categories/" + id;
        String pick = "/picks/" + id;
        List<String> routes = List.of(
                "POST /picks-templates", "GET /picks-templates",
                "GET " + template, "PATCH " + template, "DELETE " + template,
                "POST " + template + "/categories", "PATCH " + category, "DELETE " + category,
                "POST " + category + "/options", "GET " + category + "/options",
                "DELETE " + category + "/options/" + id,
                "POST " + template + "/picks", "GET " + template + "/picks", "GET " + template + "/my-picks",
                "GET " + pick, "PATCH " + pick, "DELETE " + pick, "GET /users/" + id + "/picks",
                "PUT " + pick + "/categories/" + id + "/selection",
                "DELETE " + pick + "/categories/" + id + "/selection");
        for (String route : routes) {
            String[] parts = route.split(" ", 2);
            HttpMethod method = HttpMethod.valueOf(parts[0]);
            mvc.perform(request(method, parts[1]).cookie(new Cookie(CookieUtil.CSRF_TOKEN_COOKIE, "test-csrf"))
                            .header("X-XSRF-TOKEN", "test-csrf"))
                    .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.status").value(401));
            if (method != HttpMethod.GET) {
                mvc.perform(request(method, parts[1]).cookie(access(owner)))
                        .andExpect(status().isForbidden()).andExpect(jsonPath("$.status").value(403));
            }
        }
        assertThat(picks.count()).isZero();
        assertThat(templates.count()).isZero();
    }

    private String deleteAfterBarrier(CyclicBarrier start, java.util.UUID pick, java.util.UUID category) throws Exception {
        start.await(5, TimeUnit.SECONDS);
        try {
            selectionService.deleteSelection(owner.getId(), pick, category);
            return "deleted";
        } catch (ConflictException exception) {
            return "conflict";
        }
    }

    private String selectedMovieTmdbId(java.util.UUID pickId, java.util.UUID categoryId) {
        return jdbc.queryForObject("""
                SELECT content.tmdb_id
                FROM pick_selections selection
                JOIN contents content ON content.id = selection.content_id
                WHERE selection.pick_id = ? AND selection.category_id = ?
                """, String.class, pickId, categoryId);
    }
}
