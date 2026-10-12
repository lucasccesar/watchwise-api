package com.watchwise.watchwise_api.pickstemplate.controller;

import com.watchwise.watchwise_api.pick.entity.PickVisibility;
import com.watchwise.watchwise_api.pick.support.PicksDomainIntegrationSupport;
import com.watchwise.watchwise_api.common.tmdb.TmdbEpisodeFullDetails;
import com.watchwise.watchwise_api.common.tmdb.TmdbLookupResult;
import com.watchwise.watchwise_api.common.tmdb.TmdbTvFullDetails;
import com.watchwise.watchwise_api.user.entity.UserRole;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.http.HttpMethod;

import java.util.UUID;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class PicksTemplateControllerIntegrationTest extends PicksDomainIntegrationSupport {

    @Test
    @DisplayName("[listTemplates] Should Return Filtered Page Metadata - When Origin Name And Pagination Are Supplied")
    void shouldReturnFilteredPageMetadataWhenOriginNameAndPaginationAreSupplied() throws Exception {
        template();
        mvc.perform(authenticated(other, HttpMethod.POST, "/picks-templates", """
                {"name":"Different","categories":[
                  {"name":"Person","group":"PRIMARY","displayOrder":1,"allowedType":"PERSON","optionMode":"OPEN"}]}
                """))
                .andExpect(status().isCreated());

        mvc.perform(authenticated(owner, HttpMethod.GET,
                        "/picks-templates?origin=COMMUNITY&name=Award&page=1&size=1&sort=RECENT", ""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.page").value(1))
                .andExpect(jsonPath("$.size").value(1))
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.totalPages").value(1))
                .andExpect(jsonPath("$.hasNext").value(false))
                .andExpect(jsonPath("$.content[0].name").value("Awards"));
    }

    @Test
    @DisplayName("[listTemplates] Should Include Creator Social State And Viewer Picks - When Explore Loads A Template")
    void shouldIncludeCreatorSocialStateAndViewerPicksWhenExploreLoadsATemplate() throws Exception {
        var template = template();
        owner.setProfilePicture("https://images.example/avatar.png");
        users.saveAndFlush(owner);
        var ownerPick = personPick(template, PickVisibility.PUBLIC);
        pickService.createPick(other.getId(), template.id(), new com.watchwise.watchwise_api.pick.dto.PickCreationDTO(
                PickVisibility.PUBLIC, java.util.List.of(new com.watchwise.watchwise_api.pick.dto.PickSelectionCreationDTO(
                        template.person(), new com.watchwise.watchwise_api.pick.dto.PickTargetDTO(null, "84", null)))));
        mvc.perform(authenticated(other, HttpMethod.POST, "/picks-templates/" + template.id() + "/like", ""))
                .andExpect(status().isNoContent());
        mvc.perform(authenticated(other, HttpMethod.POST, "/picks-templates/" + template.id() + "/comments",
                        "{\"text\":\"Nice template\"}"))
                .andExpect(status().isCreated());

        mvc.perform(authenticated(owner, HttpMethod.GET, "/picks-templates?name=Awards", ""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].creator.id").value(owner.getId().toString()))
                .andExpect(jsonPath("$.content[0].creator.username").value("owner"))
                .andExpect(jsonPath("$.content[0].creator.profilePicture").value("https://images.example/avatar.png"))
                .andExpect(jsonPath("$.content[0].origin").value("COMMUNITY"))
                .andExpect(jsonPath("$.content[0].picksCount").value(2))
                .andExpect(jsonPath("$.content[0].likesCount").value(1))
                .andExpect(jsonPath("$.content[0].commentsCount").value(1))
                .andExpect(jsonPath("$.content[0].isLikedByViewer").value(false))
                .andExpect(jsonPath("$.content[0].myPicksCount").value(1))
                .andExpect(jsonPath("$.content[0].latestMyPickId").value(ownerPick.id().toString()));
    }

    @Test
    @DisplayName("[createTemplate] Should Persist All Supported Targets And Eligibility - When One Aggregate Contains Fixed And Open Categories")
    void shouldPersistAllSupportedTargetsAndEligibilityWhenOneAggregateContainsFixedAndOpenCategories() throws Exception {
        when(tmdb.getTvFullDetails(org.mockito.ArgumentMatchers.eq("1396"), org.mockito.ArgumentMatchers.anyString()))
                .thenReturn(new TmdbLookupResult.Found<>(new TmdbTvFullDetails("1396", "Series", null, null,
                        null, null, "2025-04-03", null, null, null, null, null, null, null, null, null,
                        null, null, null, null, "Returning Series")));
        when(tmdb.getEpisodeFullDetails(org.mockito.ArgumentMatchers.eq("1396"), org.mockito.ArgumentMatchers.eq(1),
                org.mockito.ArgumentMatchers.eq(2), org.mockito.ArgumentMatchers.anyString()))
                .thenReturn(new TmdbLookupResult.Found<>(new TmdbEpisodeFullDetails(2, "Episode", null,
                        "2025-06-01", 2, 1, 40, null, List.of())));
        when(tmdb.getMovieFullDetails(org.mockito.ArgumentMatchers.eq("700"), org.mockito.ArgumentMatchers.anyString()))
                .thenReturn(new TmdbLookupResult.Found<>(movie("700", "2010-01-01")));

        String body = """
                {"name":"Target coverage","eligibilityStartDate":"2025-01-01","eligibilityEndDate":"2025-12-31","categories":[
                  {"name":"Movie","group":"PRIMARY","displayOrder":1,"allowedType":"MOVIE","optionMode":"FIXED","options":[{"target":{"content":{"type":"MOVIE","tmdbId":"550"}}}]},
                  {"name":"Series","group":"PRIMARY","displayOrder":2,"allowedType":"SERIES","optionMode":"FIXED","options":[{"target":{"content":{"type":"SERIES","tmdbId":"1396"}}}]},
                  {"name":"Episode","group":"PRIMARY","displayOrder":3,"allowedType":"EPISODE","optionMode":"FIXED","options":[{"target":{"content":{"type":"EPISODE","seriesTmdbId":"1396","seasonNumber":1,"episodeNumber":2}}}]},
                  {"name":"Person","group":"PRIMARY","displayOrder":4,"allowedType":"PERSON","optionMode":"FIXED","options":[{"target":{"personTmdbId":"42","contextContent":{"type":"MOVIE","tmdbId":"700"}}}]},
                  {"name":"Open","group":"SECONDARY","displayOrder":5,"allowedType":"PERSON","optionMode":"OPEN","options":[]}]}
                """;

        mvc.perform(authenticated(owner, HttpMethod.POST, "/picks-templates", body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.eligibilityStartDate").value("2025-01-01"))
                .andExpect(jsonPath("$.eligibilityEndDate").value("2025-12-31"))
                .andExpect(jsonPath("$.categories.length()").value(5))
                .andExpect(jsonPath("$.categories[0].options.length()").value(1))
                .andExpect(jsonPath("$.categories[1].options[0].target.content.tmdbId").value("1396"))
                .andExpect(jsonPath("$.categories[2].options[0].target.content.seriesTmdbId").value("1396"))
                .andExpect(jsonPath("$.categories[2].options[0].target.content.seasonNumber").value(1))
                .andExpect(jsonPath("$.categories[2].options[0].target.content.episodeNumber").value(2))
                .andExpect(jsonPath("$.categories[3].options[0].target.personTmdbId").value("42"))
                .andExpect(jsonPath("$.categories[3].options[0].target.contextContent.tmdbId").value("700"))
                .andExpect(jsonPath("$.categories[4].optionMode").value("OPEN"))
                .andExpect(jsonPath("$.categories[4].options").isEmpty());

        assertThat(templates.count()).isEqualTo(1);
        assertThat(options.count()).isEqualTo(4);
    }

    @Test
    @DisplayName("[createTemplate] Should Return ApiError 404 - When TMDB Does Not Find A Fixed Movie Target")
    void shouldReturnApiError404WhenTmdbDoesNotFindAFixedMovieTarget() throws Exception {
        when(tmdb.getMovieFullDetails("999", "en-US")).thenReturn(new TmdbLookupResult.NotFound<>());

        mvc.perform(authenticated(owner, HttpMethod.POST, "/picks-templates",
                        "{\"name\":\"Missing target\",\"categories\":[" + categoryBody("MOVIE",
                                "[{\"target\":{\"content\":{\"type\":\"MOVIE\",\"tmdbId\":\"999\"}}}]") + "]}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(jsonPath("$.error").value("Not Found"))
                .andExpect(jsonPath("$.message").value("movie was not found in TMDB"));
    }

    @Test
    @DisplayName("[createTemplate] Should Return ApiError 502 - When TMDB Is Unavailable For A Fixed Movie Target")
    void shouldReturnApiError502WhenTmdbIsUnavailableForAFixedMovieTarget() throws Exception {
        when(tmdb.getMovieFullDetails("999", "en-US")).thenReturn(new TmdbLookupResult.Unavailable<>());

        mvc.perform(authenticated(owner, HttpMethod.POST, "/picks-templates",
                        "{\"name\":\"Unavailable target\",\"categories\":[" + categoryBody("MOVIE",
                                "[{\"target\":{\"content\":{\"type\":\"MOVIE\",\"tmdbId\":\"999\"}}}]") + "]}"))
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.status").value(502))
                .andExpect(jsonPath("$.error").value("Bad Gateway"))
                .andExpect(jsonPath("$.message").value("TMDB is currently unavailable"));
    }

    @Test
    @DisplayName("[getMyTemplatesView] Should Return Only Owned Templates In The Requested Page - When Other Creators Have Templates")
    void shouldReturnOnlyOwnedTemplatesInRequestedPageWhenOtherCreatorsHaveTemplates() throws Exception {
        template();
        mvc.perform(authenticated(other, HttpMethod.POST, "/picks-templates", """
                {"name":"Other Template","categories":[
                  {"name":"Person","group":"PRIMARY","displayOrder":1,"allowedType":"PERSON","optionMode":"OPEN"}]}
                """))
                .andExpect(status().isCreated());

        mvc.perform(authenticated(owner, HttpMethod.GET, "/users/me/picks-templates/view?page=1&size=1&sort=RECENT", ""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.page").value(1))
                .andExpect(jsonPath("$.size").value(1))
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].name").value("Awards"));
    }

    @Test
    @DisplayName("[getMyTemplatesView] Should Return ApiError - When Pagination Is Invalid")
    void shouldReturnApiErrorWhenOwnedTemplatePaginationIsInvalid() throws Exception {
        mvc.perform(authenticated(owner, HttpMethod.GET, "/users/me/picks-templates/view?page=0&size=-1", ""))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.error").value("Bad Request"));
    }

    @ParameterizedTest
    @CsvSource({"MOVIE,false", "PERSON,false", "PERSON,true"})
    @DisplayName("[createTemplate] Should Roll Back Aggregate With 409 - When Initial Targets Are Duplicated")
    void shouldRollBackAggregateWhenInitialTargetsAreDuplicated(String type, boolean contextual) throws Exception {
        String target = type.equals("MOVIE") ? "{\"content\":{\"type\":\"MOVIE\",\"tmdbId\":\"550\"}}"
                : contextual ? "{\"personTmdbId\":\"42\",\"contextContent\":{\"type\":\"MOVIE\",\"tmdbId\":\"550\"}}"
                : "{\"personTmdbId\":\"42\"}";
        String option = "{\"target\":" + target + "}";
        String body = "{\"name\":\"Duplicate\",\"categories\":[" + categoryBody(type, "[" + option + "," + option + "]") + "]}";

        mvc.perform(authenticated(owner, HttpMethod.POST, "/picks-templates", body))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.status").value(409))
                .andExpect(jsonPath("$.message").value("This target is already a fixed option for this category"));

        assertThat(templates.count()).isZero();
        assertThat(categories.count()).isZero();
        assertThat(options.count()).isZero();
    }

    @Test
    @DisplayName("[createTemplate] Should Return 400 Without Persistence - When Initial Option Is Null")
    void shouldRejectNullInitialOptionWithoutPersistence() throws Exception {
        mvc.perform(authenticated(owner, HttpMethod.POST, "/picks-templates",
                        "{\"name\":\"Null\",\"categories\":[" + categoryBody("PERSON", "[null]") + "]}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.status").value(400));

        assertThat(templates.count()).isZero();
        assertThat(categories.count()).isZero();
        assertThat(options.count()).isZero();
    }

    @Test
    @DisplayName("[detachOrDeleteTemplate] Should Preserve Picks And Origin - When Creator Detaches Used Template")
    void shouldPreservePicksWhenCreatorDetachesUsedTemplate() throws Exception {
        var template = template();
        var pick = personPick(template, PickVisibility.PUBLIC);
        String path = "/picks-templates/" + template.id();
        mvc.perform(authenticated(other, HttpMethod.PATCH, path, "{\"name\":\"Intrusion\"}"))
                .andExpect(status().isForbidden());
        mvc.perform(authenticated(owner, HttpMethod.DELETE, path, "")).andExpect(status().isNoContent());
        mvc.perform(authenticated(other, HttpMethod.GET, path, ""))
                .andExpect(status().isOk()).andExpect(jsonPath("$.creator").isEmpty())
                .andExpect(jsonPath("$.origin").value("COMMUNITY"));
        mvc.perform(authenticated(owner, HttpMethod.PATCH, path, "{\"name\":\"Former\"}"))
                .andExpect(status().isForbidden());
        mvc.perform(authenticated(admin, HttpMethod.PATCH, path, "{\"name\":\"Admin Edit\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.name").value("Admin Edit"));

        assertThat(picks.existsById(pick.id())).isTrue();
        assertThat(selections.countByPickId(pick.id())).isEqualTo(1);
    }

    @Test
    @DisplayName("[createTemplate] Should Derive Origin From Current Role - When Token Predates Promotion")
    void shouldUsePersistedRoleAndDeleteUnusedAggregate() throws Exception {
        var tokenRequest = authenticated(other, HttpMethod.POST, "/picks-templates",
                "{\"name\":\"Official\",\"categories\":[{\"name\":\"Person\",\"group\":\"PRIMARY\",\"displayOrder\":1,\"allowedType\":\"PERSON\",\"optionMode\":\"OPEN\"}]}");
        other.setRole(UserRole.ADMIN);
        users.saveAndFlush(other);
        var result = mvc.perform(tokenRequest).andExpect(status().isCreated())
                .andExpect(jsonPath("$.origin").value("OFFICIAL")).andReturn();
        UUID id = id(result, "$.id");
        mvc.perform(authenticated(admin, HttpMethod.DELETE, "/picks-templates/" + id, ""))
                .andExpect(status().isNoContent());

        assertThat(templates.existsById(id)).isFalse();
        assertThat(categories.count()).isZero();
        assertThat(options.count()).isZero();
    }

    @Test
    @DisplayName("[updateTemplate] Should Return 409 - When Period Changes After Content Selection")
    void shouldRejectPeriodChangeAfterContentSelection() throws Exception {
        var template = template();
        var pick = personPick(template, PickVisibility.PUBLIC);
        selectionService.upsertSelection(owner.getId(), pick.id(), template.movie(), movieTarget("550"));

        mvc.perform(authenticated(owner, HttpMethod.PATCH, "/picks-templates/" + template.id(),
                        "{\"eligibilityStartDate\":\"2026-01-01\",\"eligibilityEndDate\":\"2026-12-31\"}"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.status").value(409));

        assertThat(templates.findById(template.id()).orElseThrow().getEligibilityStartDate()).isNull();
    }

    @Test
    @DisplayName("[listTemplates] Should Return ApiError - When Sort Is Unsupported")
    void shouldReturnApiErrorWhenSortIsUnsupported() throws Exception {
        mvc.perform(authenticated(owner, HttpMethod.GET, "/picks-templates?sort=UNSUPPORTED", ""))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.error").value("Bad Request"))
                .andExpect(jsonPath("$.message").value("Invalid value 'UNSUPPORTED' for parameter 'sort'. Expected type: PicksTemplateSort. Accepted values: POPULAR_WEEK, MOST_PICKED, RECENT"))
                .andExpect(jsonPath("$.path").value("/picks-templates"));
    }
}
