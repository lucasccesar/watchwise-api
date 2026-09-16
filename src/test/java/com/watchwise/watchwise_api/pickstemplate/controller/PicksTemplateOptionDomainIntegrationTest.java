package com.watchwise.watchwise_api.pickstemplate.controller;

import com.watchwise.watchwise_api.pick.dto.PickCreationDTO;
import com.watchwise.watchwise_api.pick.dto.PickSelectionCreationDTO;
import com.watchwise.watchwise_api.pick.dto.PickTargetDTO;
import com.watchwise.watchwise_api.pick.entity.PickVisibility;
import com.watchwise.watchwise_api.pick.support.PicksDomainIntegrationSupport;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.http.HttpMethod;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class PicksTemplateOptionDomainIntegrationTest extends PicksDomainIntegrationSupport {

    @ParameterizedTest
    @CsvSource({"MOVIE,false", "PERSON,false", "PERSON,true"})
    @DisplayName("[addOption] Should Return 409 Without Partial Write - When Target Already Exists")
    void shouldRejectRealDuplicateTargets(String type, boolean contextual) throws Exception {
        var template = template();
        var created = mvc.perform(authenticated(owner, HttpMethod.POST,
                        "/picks-templates/" + template.id() + "/categories", categoryBody(type, "[]")))
                .andExpect(status().isCreated()).andReturn();
        String path = "/picks-templates/" + template.id() + "/categories/" + id(created, "$.id") + "/options";
        String target = type.equals("MOVIE") ? "{\"content\":{\"type\":\"MOVIE\",\"tmdbId\":\"550\"}}"
                : contextual ? "{\"personTmdbId\":\"42\",\"contextContent\":{\"type\":\"MOVIE\",\"tmdbId\":\"550\"}}"
                : "{\"personTmdbId\":\"42\"}";
        String body = "{\"target\":" + target + "}";
        mvc.perform(authenticated(owner, HttpMethod.POST, path, body)).andExpect(status().isCreated());
        mvc.perform(authenticated(owner, HttpMethod.POST, path, body))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.status").value(409));
        assertThat(options.count()).isEqualTo(1);
        if (type.equals("PERSON")) {
            String distinct = contextual ? "{\"target\":{\"personTmdbId\":\"42\"}}"
                    : "{\"target\":{\"personTmdbId\":\"42\",\"contextContent\":{\"type\":\"MOVIE\",\"tmdbId\":\"550\"}}}";
            mvc.perform(authenticated(owner, HttpMethod.POST, path, distinct)).andExpect(status().isCreated());
            assertThat(options.count()).isEqualTo(2);
        }
    }

    @Test
    @DisplayName("[deleteOption] Should Freeze Used Option And List Locally - When Pick Selects Fixed Person")
    void shouldFreezeUsedOptionAndListLocally() throws Exception {
        var template = template();
        var created = mvc.perform(authenticated(owner, HttpMethod.POST,
                        "/picks-templates/" + template.id() + "/categories",
                        categoryBody("PERSON", "[{\"target\":{\"personTmdbId\":\"42\"}},{\"target\":{\"personTmdbId\":\"84\"}}]")))
                .andExpect(status().isCreated()).andReturn();
        var category = id(created, "$.id");
        var optionRows = options.findByCategoryId(category);
        var used = optionRows.stream().filter(option -> "42".equals(option.getPersonTmdbId())).findFirst().orElseThrow().getId();
        var unused = optionRows.stream().filter(option -> "84".equals(option.getPersonTmdbId())).findFirst().orElseThrow().getId();
        pickService.createPick(owner.getId(), template.id(), new PickCreationDTO(PickVisibility.PUBLIC,
                List.of(new PickSelectionCreationDTO(category, new PickTargetDTO(null, "42", null)))));
        String path = "/picks-templates/" + template.id() + "/categories/" + category + "/options";
        mvc.perform(authenticated(admin, HttpMethod.DELETE, path + "/" + used, "")).andExpect(status().isConflict());
        mvc.perform(authenticated(owner, HttpMethod.DELETE, path + "/" + unused, "")).andExpect(status().isNoContent());
        assertThat(options.existsById(used)).isTrue();
        assertThat(options.existsById(unused)).isFalse();
        clearInvocations(tmdb);
        mvc.perform(authenticated(other, HttpMethod.GET, path, ""))
                .andExpect(status().isOk()).andExpect(jsonPath("$.totalElements").value(1));
        verifyNoInteractions(tmdb);
    }
}
