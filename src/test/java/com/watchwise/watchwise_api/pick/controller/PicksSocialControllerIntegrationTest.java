package com.watchwise.watchwise_api.pick.controller;

import com.watchwise.watchwise_api.pick.dto.PickResponseDTO;
import com.watchwise.watchwise_api.pick.entity.PickVisibility;
import com.watchwise.watchwise_api.pick.support.PicksDomainIntegrationSupport;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;

import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class PicksSocialControllerIntegrationTest extends PicksDomainIntegrationSupport {

    @Test
    void shouldLikeAndCommentOnPickAndTemplate() throws Exception {
        TemplateFixture fixture = template();
        PickResponseDTO pick = personPick(fixture, PickVisibility.PUBLIC);

        mvc.perform(authenticated(owner, HttpMethod.GET, "/picks/" + pick.id() + "/comments", ""))
                .andExpect(status().isOk());
        mvc.perform(authenticated(owner, HttpMethod.POST, "/picks/" + pick.id() + "/comments",
                        "{\"text\":\"Pick comment\"}"))
                .andExpect(status().isCreated());
        mvc.perform(authenticated(owner, HttpMethod.POST, "/picks/" + pick.id() + "/like", ""))
                .andExpect(status().isNoContent());
        mvc.perform(authenticated(owner, HttpMethod.DELETE, "/picks/" + pick.id() + "/like", ""))
                .andExpect(status().isNoContent());

        mvc.perform(authenticated(owner, HttpMethod.GET, "/picks-templates/" + fixture.id() + "/comments", ""))
                .andExpect(status().isOk());
        mvc.perform(authenticated(owner, HttpMethod.POST, "/picks-templates/" + fixture.id() + "/comments",
                        "{\"text\":\"Template comment\"}"))
                .andExpect(status().isCreated());
        mvc.perform(authenticated(owner, HttpMethod.POST, "/picks-templates/" + fixture.id() + "/like", ""))
                .andExpect(status().isNoContent());
        mvc.perform(authenticated(owner, HttpMethod.DELETE, "/picks-templates/" + fixture.id() + "/like", ""))
                .andExpect(status().isNoContent());
    }

    @Test
    void shouldRejectPrivatePickReadAndCrossTargetParentComment() throws Exception {
        TemplateFixture fixture = template();
        PickResponseDTO privatePick = personPick(fixture, PickVisibility.PRIVATE);

        mvc.perform(authenticated(other, HttpMethod.GET, "/picks/" + privatePick.id() + "/comments", ""))
                .andExpect(status().isForbidden());
        mvc.perform(authenticated(other, HttpMethod.POST, "/picks/" + privatePick.id() + "/like", ""))
                .andExpect(status().isForbidden());

        PickResponseDTO publicPick = personPick(fixture, PickVisibility.PUBLIC);
        var parent = mvc.perform(authenticated(owner, HttpMethod.POST, "/picks/" + publicPick.id() + "/comments",
                        "{\"text\":\"Parent\"}"))
                .andExpect(status().isCreated())
                .andReturn();
        String parentId = com.jayway.jsonpath.JsonPath.read(parent.getResponse().getContentAsString(), "$.id");

        mvc.perform(authenticated(owner, HttpMethod.POST, "/picks-templates/" + fixture.id() + "/comments",
                        "{\"text\":\"Wrong target reply\",\"parentCommentId\":\"" + parentId + "\"}"))
                .andExpect(status().isBadRequest());
    }
}
