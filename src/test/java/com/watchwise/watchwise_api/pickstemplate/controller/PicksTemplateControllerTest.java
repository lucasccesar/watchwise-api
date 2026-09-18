package com.watchwise.watchwise_api.pickstemplate.controller;

import com.watchwise.watchwise_api.pickstemplate.dto.PicksTemplateCreationDTO;
import com.watchwise.watchwise_api.pickstemplate.dto.PicksTemplatePreviewDTO;
import com.watchwise.watchwise_api.pickstemplate.dto.PicksTemplateResponseDTO;
import com.watchwise.watchwise_api.pickstemplate.dto.PicksTemplateSort;
import com.watchwise.watchwise_api.pickstemplate.entity.PickOrigin;
import com.watchwise.watchwise_api.pickstemplate.service.PicksTemplateService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
class PicksTemplateControllerTest {
    @Mock PicksTemplateService service;
    @InjectMocks PicksTemplateController controller;
    MockMvc mockMvc;
    UUID actorId;
    @BeforeEach void setUp() { actorId = UUID.randomUUID(); SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(actorId, null, List.of())); mockMvc = MockMvcBuilders.standaloneSetup(controller).build(); }
    @AfterEach void cleanUp() { SecurityContextHolder.clearContext(); }
    @Test void shouldMapAllTemplateRoutesThroughMvc() throws Exception {
        PicksTemplateCreationDTO dto = new PicksTemplateCreationDTO("Awards", null, null, null, null, null, List.of());
        PicksTemplateResponseDTO response = new PicksTemplateResponseDTO(UUID.randomUUID(), null, null, "Awards", null, null, null, null, null, null, null, List.of());
        when(service.createTemplate(eq(actorId), any())).thenReturn(response);
        UUID templateId = response.id();
        when(service.listTemplates(actorId, PickOrigin.COMMUNITY, "Awards", 1, 20, null)).thenReturn(new PageImpl<>(List.of(new PicksTemplatePreviewDTO(templateId, PickOrigin.COMMUNITY, "Awards", null, null)), PageRequest.of(0, 20), 1));
        when(service.getTemplate(actorId, templateId)).thenReturn(response);
        when(service.updateTemplate(eq(actorId), eq(templateId), any())).thenReturn(response);

        mockMvc.perform(post("/picks-templates").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Awards\",\"categories\":[{\"name\":\"Best\",\"group\":\"PRIMARY\",\"displayOrder\":1,\"allowedType\":\"MOVIE\",\"optionMode\":\"FIXED\"}]}")).andExpect(status().isCreated());
        mockMvc.perform(get("/picks-templates").param("origin", "COMMUNITY").param("name", "Awards").param("page", "1").param("size", "20")).andExpect(status().isOk());
        verify(service).listTemplates(actorId, PickOrigin.COMMUNITY, "Awards", 1, 20, null);
        mockMvc.perform(get("/picks-templates/{id}", templateId)).andExpect(status().isOk());
        mockMvc.perform(patch("/picks-templates/{id}", templateId).contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"Changed\"}")).andExpect(status().isOk());
        mockMvc.perform(delete("/picks-templates/{id}", templateId)).andExpect(status().isNoContent());
        verify(service).detachOrDeleteTemplate(actorId, templateId);
    }

    @ParameterizedTest
    @EnumSource(PicksTemplateSort.class)
    void shouldBindAndForwardTemplateSort(PicksTemplateSort sort) throws Exception {
        when(service.listTemplates(eq(actorId), isNull(), isNull(), isNull(), isNull(), eq(sort)))
                .thenReturn(Page.empty());

        mockMvc.perform(get("/picks-templates").param("sort", sort.name()))
                .andExpect(status().isOk());

        verify(service).listTemplates(actorId, null, null, null, null, sort);
    }
}
