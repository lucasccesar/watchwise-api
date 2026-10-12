package com.watchwise.watchwise_api.pick.controller;

import com.watchwise.watchwise_api.pick.entity.PickVisibility;
import com.watchwise.watchwise_api.pick.support.PicksDomainIntegrationSupport;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;

import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class PickViewControllerIntegrationTest extends PicksDomainIntegrationSupport {

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
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/users/me/picks/view"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.status").value(401));
    }
}
