package com.watchwise.watchwise_api.pickstemplate.controller;

import com.watchwise.watchwise_api.pick.entity.PickVisibility;
import com.watchwise.watchwise_api.pick.support.PicksDomainIntegrationSupport;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class PicksTemplateCategoryControllerIntegrationTest extends PicksDomainIntegrationSupport {

    @Test
    @DisplayName("[addCategory] Should Roll Back Category With 409 - When Initial Person Targets Are Duplicated")
    void shouldRollBackCategoryWhenInitialTargetsAreDuplicated() throws Exception {
        var template = template();
        mvc.perform(authenticated(owner, HttpMethod.POST, "/picks-templates/" + template.id() + "/categories",
                        categoryBody("PERSON", "[{\"target\":{\"personTmdbId\":\"42\"}},{\"target\":{\"personTmdbId\":\"42\"}}]")))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.status").value(409));

        assertThat(categories.count()).isEqualTo(2);
        assertThat(options.count()).isZero();
        assertThat(templates.count()).isEqualTo(1);
    }

    @Test
    @DisplayName("[addCategory] Should Return 400 Without Partial Persistence - When Initial Option Is Null")
    void shouldRejectNullOptionWithoutPartialPersistence() throws Exception {
        var template = template();
        mvc.perform(authenticated(owner, HttpMethod.POST, "/picks-templates/" + template.id() + "/categories",
                        categoryBody("PERSON", "[null]")))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.status").value(400));

        assertThat(categories.count()).isEqualTo(2);
        assertThat(options.count()).isZero();
    }

    @Test
    @DisplayName("[updateCategory] Should Freeze Used Category And Allow Unused CRUD - When Pick Exists")
    void shouldFreezeUsedCategoryAndAllowUnusedCrud() throws Exception {
        var template = template();
        personPick(template, PickVisibility.PUBLIC);
        String base = "/picks-templates/" + template.id() + "/categories/";
        mvc.perform(authenticated(owner, HttpMethod.PATCH, base + template.person(), "{\"name\":\"Changed\"}"))
                .andExpect(status().isConflict());
        mvc.perform(authenticated(admin, HttpMethod.DELETE, base + template.person(), ""))
                .andExpect(status().isConflict());
        mvc.perform(authenticated(other, HttpMethod.PATCH, base + template.movie(), "{\"name\":\"Forbidden\"}"))
                .andExpect(status().isForbidden());
        mvc.perform(authenticated(admin, HttpMethod.PATCH, base + template.movie(), "{\"name\":\"Updated\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.name").value("Updated"));
        assertThat(categories.findById(template.movie()).orElseThrow().getName()).isEqualTo("Updated");
        mvc.perform(authenticated(owner, HttpMethod.DELETE, base + template.movie(), ""))
                .andExpect(status().isNoContent());
        assertThat(categories.existsById(template.movie())).isFalse();
        assertThat(categories.findById(template.person()).orElseThrow().getName()).isEqualTo("Person");
    }
}
