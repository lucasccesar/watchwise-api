package com.watchwise.watchwise_api.pickstemplate.controller;

import com.watchwise.watchwise_api.pick.entity.PickVisibility;
import com.watchwise.watchwise_api.pick.support.PicksDomainIntegrationSupport;
import com.watchwise.watchwise_api.user.entity.UserRole;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.http.HttpMethod;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class PicksTemplateControllerIntegrationTest extends PicksDomainIntegrationSupport {

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
                "{\"name\":\"Official\",\"categories\":[" + categoryBody("PERSON", "[]") + "]}");
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
