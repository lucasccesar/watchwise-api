package com.watchwise.watchwise_api.pickstemplate.controller;

import com.watchwise.watchwise_api.pick.dto.PickOptionSearchDTO;
import com.watchwise.watchwise_api.pickstemplate.dto.PicksTemplateOptionDTO;
import com.watchwise.watchwise_api.pickstemplate.service.PicksTemplateOptionService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
class PicksTemplateOptionControllerTest {
    @Mock PicksTemplateOptionService service;
    @InjectMocks PicksTemplateOptionController controller;
    MockMvc mockMvc;
    UUID actorId;
    @BeforeEach void setUp() { actorId = UUID.randomUUID(); SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(actorId, null, List.of())); mockMvc = MockMvcBuilders.standaloneSetup(controller).build(); }
    @AfterEach void cleanUp() { SecurityContextHolder.clearContext(); }
    @Test void shouldMapAllOptionRoutesThroughMvcAndBindQ() throws Exception {
        UUID templateId = UUID.randomUUID();
        UUID categoryId = UUID.randomUUID();
        UUID optionId = UUID.randomUUID();
        when(service.addOption(eq(actorId), eq(templateId), eq(categoryId), any())).thenReturn(new PicksTemplateOptionDTO(optionId, null, null));
        when(service.searchOptions(actorId, templateId, categoryId, "Ada", null, null, 1, 20))
                .thenReturn(new PageImpl<>(List.<PickOptionSearchDTO>of(), PageRequest.of(0, 20), 0));

        mockMvc.perform(post("/picks-templates/{templateId}/categories/{categoryId}/options", templateId, categoryId)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"target\":{\"content\":{\"type\":\"MOVIE\",\"tmdbId\":\"550\"}}}")).andExpect(status().isCreated());
        mockMvc.perform(get("/picks-templates/{templateId}/categories/{categoryId}/options", templateId, categoryId)
                        .param("q", "Ada").param("page", "1").param("size", "20")).andExpect(status().isOk());
        mockMvc.perform(delete("/picks-templates/{templateId}/categories/{categoryId}/options/{optionId}", templateId, categoryId, optionId)).andExpect(status().isNoContent());
        verify(service).searchOptions(actorId, templateId, categoryId, "Ada", null, null, 1, 20);
    }
}
