package com.watchwise.watchwise_api.pick.controller;

import com.watchwise.watchwise_api.pick.entity.PickVisibility;
import com.watchwise.watchwise_api.pick.dto.PickCreationDTO;
import com.watchwise.watchwise_api.pick.dto.PickSelectionCreationDTO;
import com.watchwise.watchwise_api.pick.dto.PickTargetDTO;
import com.watchwise.watchwise_api.pick.support.PicksDomainIntegrationSupport;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;

import java.util.UUID;

import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

class PickViewControllerIntegrationTest extends PicksDomainIntegrationSupport {

    @Test
    @DisplayName("[getTemplatePicksView] Should Return Recent Visual Page - When Picks Are Visible")
    void shouldReturnRecentVisualTemplatePicksPageWhenPicksAreVisible() throws Exception {
        var template = template();
        var pick = personPick(template, PickVisibility.PUBLIC);
        addCommentAndLike(pick.id());

        mvc.perform(authenticated(owner, HttpMethod.GET,
                        "/picks-templates/" + template.id() + "/picks/view?sort=RECENT&page=1&size=10", ""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.page").value(1))
                .andExpect(jsonPath("$.size").value(10))
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.totalPages").value(1))
                .andExpect(jsonPath("$.hasNext").value(false))
                .andExpect(jsonPath("$.content[0].id").value(pick.id().toString()))
                .andExpect(jsonPath("$.content[0].user.id").value(owner.getId().toString()))
                .andExpect(jsonPath("$.content[0].progress").value("PARTIAL"))
                .andExpect(jsonPath("$.content[0].answeredCategoriesCount").value(1))
                .andExpect(jsonPath("$.content[0].totalCategoriesCount").value(2))
                .andExpect(jsonPath("$.content[0].answeredCategories.length()").value(1))
                .andExpect(jsonPath("$.content[0].answeredCategories[0].preview.person.tmdbId").value("42"))
                .andExpect(jsonPath("$.content[0].likesCount").value(1))
                .andExpect(jsonPath("$.content[0].commentsCount").value(1))
                .andExpect(jsonPath("$.content[0].isLikedByViewer").value(true));
    }

    @Test
    @DisplayName("[getTemplatePicksView] Should Return Popular Visual Page - When Sort Is Popular")
    void shouldReturnPopularVisualTemplatePicksPageWhenSortIsPopular() throws Exception {
        var template = template();
        var pick = personPick(template, PickVisibility.PUBLIC);

        mvc.perform(authenticated(owner, HttpMethod.GET,
                        "/picks-templates/" + template.id() + "/picks/view?sort=POPULAR&page=1&size=10", ""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].id").value(pick.id().toString()))
                .andExpect(jsonPath("$.page").value(1))
                .andExpect(jsonPath("$.size").value(10))
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.totalPages").value(1))
                .andExpect(jsonPath("$.hasNext").value(false));
    }

    @Test
    @DisplayName("[getMyPicksView] Should Return Only Current Viewer's Template Picks")
    void shouldReturnOnlyCurrentViewersTemplatePicks() throws Exception {
        var template = template();
        var owned = personPick(template, PickVisibility.PRIVATE);
        pickService.createPick(other.getId(), template.id(),
                new PickCreationDTO(PickVisibility.PUBLIC,
                        java.util.List.of(new PickSelectionCreationDTO(template.person(),
                                new PickTargetDTO(null, "43", null)))));

        mvc.perform(authenticated(owner, HttpMethod.GET,
                        "/picks-templates/" + template.id() + "/my-picks/view?page=1&size=10", ""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.page").value(1))
                .andExpect(jsonPath("$.size").value(10))
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].id").value(owned.id().toString()))
                .andExpect(jsonPath("$.content[0].user.id").value(owner.getId().toString()))
                .andExpect(jsonPath("$.content[0].answeredCategories.length()").value(1));
    }

    @Test
    @DisplayName("[getPickView] Should Return Full Visual Detail - Including Unanswered Categories")
    void shouldReturnFullVisualDetailIncludingUnansweredCategories() throws Exception {
        var template = template();
        var pick = personPick(template, PickVisibility.PUBLIC);
        addCommentAndLike(pick.id());

        mvc.perform(authenticated(owner, HttpMethod.GET, "/picks/" + pick.id() + "/view", ""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(pick.id().toString()))
                .andExpect(jsonPath("$.user.id").value(owner.getId().toString()))
                .andExpect(jsonPath("$.picksTemplate.id").value(template.id().toString()))
                .andExpect(jsonPath("$.picksTemplate.creator.id").value(owner.getId().toString()))
                .andExpect(jsonPath("$.progress").value("PARTIAL"))
                .andExpect(jsonPath("$.answeredCategoriesCount").value(1))
                .andExpect(jsonPath("$.totalCategoriesCount").value(2))
                .andExpect(jsonPath("$.answers.length()").value(2))
                .andExpect(jsonPath("$.answers[0].isAnswered").value(true))
                .andExpect(jsonPath("$.answers[0].preview.person.tmdbId").value("42"))
                .andExpect(jsonPath("$.answers[1].isAnswered").value(false))
                .andExpect(jsonPath("$.answers[1].target").doesNotExist())
                .andExpect(jsonPath("$.likesCount").value(1))
                .andExpect(jsonPath("$.commentsCount").value(1))
                .andExpect(jsonPath("$.isLikedByViewer").value(true));

        mvc.perform(authenticated(other, HttpMethod.GET, "/picks/" + pick.id() + "/view", ""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.likesCount").value(1))
                .andExpect(jsonPath("$.commentsCount").value(1))
                .andExpect(jsonPath("$.isLikedByViewer").value(false));
    }

    @Test
    @DisplayName("[getPickView] Should Return Forbidden - When Another User's Pick Is Private")
    void shouldReturnForbiddenWhenAnotherUsersPickIsPrivate() throws Exception {
        var template = template();
        var privatePick = pickService.createPick(other.getId(), template.id(),
                new PickCreationDTO(PickVisibility.PRIVATE,
                        java.util.List.of(new PickSelectionCreationDTO(template.person(),
                                new PickTargetDTO(null, "43", null)))));

        mvc.perform(authenticated(owner, HttpMethod.GET, "/picks/" + privatePick.id() + "/view", ""))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.status").value(403));
    }

    @Test
    @DisplayName("[getTemplatePicksView] Should Hide Follower Pick - When Viewer Is Not An Accepted Follower")
    void shouldHideFollowerPickWhenViewerIsNotAnAcceptedFollower() throws Exception {
        var template = template();
        var followerPick = pickService.createPick(other.getId(), template.id(),
                new PickCreationDTO(PickVisibility.FOLLOWERS,
                        java.util.List.of(new PickSelectionCreationDTO(template.person(),
                                new PickTargetDTO(null, "43", null)))));

        mvc.perform(authenticated(owner, HttpMethod.GET,
                        "/picks-templates/" + template.id() + "/picks/view?sort=RECENT&page=1&size=10", ""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content").isEmpty())
                .andExpect(jsonPath("$.totalElements").value(0));
    }

    @Test
    @DisplayName("[getTemplatePicksView] Should Return Not Found - When Template Does Not Exist")
    void shouldReturnNotFoundWhenTemplateDoesNotExist() throws Exception {
        mvc.perform(authenticated(owner, HttpMethod.GET,
                        "/picks-templates/" + UUID.randomUUID() + "/picks/view?sort=RECENT&page=1&size=10", ""))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404));
    }

    @Test
    @DisplayName("[getPickView] Should Keep Reading Pick - When Template Creator Is Detached")
    void shouldKeepReadingPickWhenTemplateCreatorIsDetached() throws Exception {
        var template = template();
        var pick = personPick(template, PickVisibility.PUBLIC);
        jdbc.update("update picks_templates set creator_id = null where id = ?", template.id());

        mvc.perform(authenticated(owner, HttpMethod.GET, "/picks/" + pick.id() + "/view", ""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.picksTemplate.id").value(template.id().toString()))
                .andExpect(jsonPath("$.picksTemplate.creator").doesNotExist())
                .andExpect(jsonPath("$.answers.length()").value(2));
    }

    @Test
    @DisplayName("[visual reads] Should Cap Page Size At Ten")
    void shouldCapVisualPageSizeAtTen() throws Exception {
        var template = template();
        personPick(template, PickVisibility.PUBLIC);

        mvc.perform(authenticated(owner, HttpMethod.GET,
                        "/picks-templates/" + template.id() + "/my-picks/view?page=1&size=99", ""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.page").value(1))
                .andExpect(jsonPath("$.size").value(10));
    }

    @Test
    @DisplayName("[visual reads] Should Return ApiError For Invalid Pagination")
    void shouldReturnApiErrorForInvalidVisualPagination() throws Exception {
        var template = template();

        mvc.perform(authenticated(owner, HttpMethod.GET,
                        "/picks-templates/" + template.id() + "/my-picks/view?page=-1&size=10", ""))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.error").value("Bad Request"));

        mvc.perform(authenticated(owner, HttpMethod.GET,
                        "/picks-templates/" + template.id() + "/my-picks/view?page=1&size=0", ""))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.error").value("Bad Request"));
    }

    private void addCommentAndLike(UUID pickId) throws Exception {
        mvc.perform(authenticated(owner, HttpMethod.POST, "/picks/" + pickId + "/comments",
                        "{\"text\":\"Great pick\"}"))
                .andExpect(status().isCreated());
        mvc.perform(authenticated(owner, HttpMethod.POST, "/picks/" + pickId + "/like", ""))
                .andExpect(status().isNoContent());
    }

    @Test
    @DisplayName("[getMyPicksView] Should Return Only Owned Picks In Paginated Cards - When Another User Has Picks")
    void shouldReturnOnlyOwnedPicksInPaginatedCardsWhenAnotherUserHasPicks() throws Exception {
        var template = template();
        var owned = personPick(template, PickVisibility.PRIVATE);
        var othersPick = pickService.createPick(other.getId(), template.id(),
                new com.watchwise.watchwise_api.pick.dto.PickCreationDTO(PickVisibility.PUBLIC,
                        java.util.List.of(new com.watchwise.watchwise_api.pick.dto.PickSelectionCreationDTO(
                                template.person(), new com.watchwise.watchwise_api.pick.dto.PickTargetDTO(null, "43", null)))));
        jdbc.update("update picks set created_at = case when id = ? then timestamp '2026-10-10 10:00:00' else timestamp '2026-10-11 10:00:00' end where id in (?, ?)",
                owned.id(), owned.id(), othersPick.id());

        mvc.perform(authenticated(owner, HttpMethod.GET, "/users/me/picks/view?page=1&size=1", ""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.page").value(1))
                .andExpect(jsonPath("$.size").value(1))
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].id").value(owned.id().toString()))
                .andExpect(jsonPath("$.content[0].picksTemplate.id").value(template.id().toString()))
                .andExpect(jsonPath("$.content[0].progress").value("PARTIAL"))
                .andExpect(jsonPath("$.content[0].answeredCategoriesCount").value(1))
                .andExpect(jsonPath("$.content[0].totalCategoriesCount").value(2))
                .andExpect(jsonPath("$.content[0].answeredCategories.length()").value(1));
    }

    @Test
    @DisplayName("[getMyPicksView] Should Cap Global Page Size At Ten - When Client Requests A Larger Size")
    void shouldCapGlobalMyPicksViewPageSizeAtTenWhenClientRequestsLargerSize() throws Exception {
        var template = template();
        var first = personPick(template, PickVisibility.PRIVATE);
        var second = personPick(template, PickVisibility.PRIVATE);

        mvc.perform(authenticated(owner, HttpMethod.GET, "/users/me/picks/view?page=1&size=99", ""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.page").value(1))
                .andExpect(jsonPath("$.size").value(10))
                .andExpect(jsonPath("$.totalElements").value(2))
                .andExpect(jsonPath("$.totalPages").value(1))
                .andExpect(jsonPath("$.hasNext").value(false))
                .andExpect(jsonPath("$.content.length()").value(2))
                .andExpect(jsonPath("$.content[*].id")
                        .value(org.hamcrest.Matchers.containsInAnyOrder(
                                first.id().toString(), second.id().toString())));
    }

    @Test
    @DisplayName("[getMyPicksView] Should Return ApiError - When Pagination Is Invalid")
    void shouldReturnApiErrorWhenPaginationIsInvalid() throws Exception {
        mvc.perform(authenticated(owner, HttpMethod.GET, "/users/me/picks/view?page=-1&size=0", ""))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.error").value("Bad Request"));
    }

    @Test
    @DisplayName("[getMyPicksView] Should Return 401 - When Viewer Is Not Authenticated")
    void shouldReturnUnauthorizedWhenViewerIsNotAuthenticated() throws Exception {
        mvc.perform(get("/users/me/picks/view"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.status").value(401))
                .andExpect(jsonPath("$.error").value("Unauthorized"))
                .andExpect(jsonPath("$.message").value("Authentication is required to access this resource"))
                .andExpect(jsonPath("$.path").value("/users/me/picks/view"));
    }

    @Test
    @DisplayName("[getTemplatePicksView] Should Return 401 - When Viewer Is Not Authenticated")
    void shouldReturnUnauthorizedForTemplatePicksViewWhenViewerIsNotAuthenticated() throws Exception {
        UUID templateId = UUID.randomUUID();

        mvc.perform(get("/picks-templates/{templateId}/picks/view", templateId))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.status").value(401))
                .andExpect(jsonPath("$.error").value("Unauthorized"))
                .andExpect(jsonPath("$.message").value("Authentication is required to access this resource"))
                .andExpect(jsonPath("$.path").value("/picks-templates/" + templateId + "/picks/view"));
    }

    @Test
    @DisplayName("[getMyPicksView] Should Return 401 - When Viewer Is Not Authenticated")
    void shouldReturnUnauthorizedForTemplateMyPicksViewWhenViewerIsNotAuthenticated() throws Exception {
        UUID templateId = UUID.randomUUID();

        mvc.perform(get("/picks-templates/{templateId}/my-picks/view", templateId))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.status").value(401))
                .andExpect(jsonPath("$.error").value("Unauthorized"))
                .andExpect(jsonPath("$.message").value("Authentication is required to access this resource"))
                .andExpect(jsonPath("$.path").value("/picks-templates/" + templateId + "/my-picks/view"));
    }

    @Test
    @DisplayName("[getPickView] Should Return 401 - When Viewer Is Not Authenticated")
    void shouldReturnUnauthorizedForPickViewWhenViewerIsNotAuthenticated() throws Exception {
        UUID pickId = UUID.randomUUID();

        mvc.perform(get("/picks/{pickId}/view", pickId))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.status").value(401))
                .andExpect(jsonPath("$.error").value("Unauthorized"))
                .andExpect(jsonPath("$.message").value("Authentication is required to access this resource"))
                .andExpect(jsonPath("$.path").value("/picks/" + pickId + "/view"));
    }
}
