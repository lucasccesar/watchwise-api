package com.watchwise.watchwise_api.pickstemplate.controller;

import com.watchwise.watchwise_api.pickstemplate.dto.PicksTemplateCategoryDTO;
import com.watchwise.watchwise_api.pickstemplate.service.PicksTemplateCategoryService;
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
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
class PicksTemplateCategoryControllerTest {
    @Mock PicksTemplateCategoryService service;
    @InjectMocks PicksTemplateCategoryController controller;
    MockMvc mockMvc;
    UUID actorId;
    @BeforeEach void setUp() { actorId = UUID.randomUUID(); SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(actorId, null, List.of())); mockMvc = MockMvcBuilders.standaloneSetup(controller).build(); }
    @AfterEach void cleanUp() { SecurityContextHolder.clearContext(); }
    @Test void shouldMapAllCategoryRoutesThroughMvc() throws Exception {
        UUID templateId = UUID.randomUUID();
        UUID categoryId = UUID.randomUUID();
        when(service.addCategory(eq(actorId), eq(templateId), any())).thenReturn(new PicksTemplateCategoryDTO(categoryId, "Best", null, null, 1, null, null, null, null, List.of()));
        when(service.updateCategory(eq(actorId), eq(templateId), eq(categoryId), any())).thenReturn(new PicksTemplateCategoryDTO(categoryId, "Best", null, null, 1, null, null, null, null, List.of()));

        mockMvc.perform(post("/picks-templates/{templateId}/categories", templateId).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Best\",\"group\":\"PRIMARY\",\"displayOrder\":1,\"allowedType\":\"MOVIE\",\"optionMode\":\"FIXED\"}")).andExpect(status().isCreated());
        mockMvc.perform(patch("/picks-templates/{templateId}/categories/{categoryId}", templateId, categoryId).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Changed\"}")).andExpect(status().isOk());
        mockMvc.perform(delete("/picks-templates/{templateId}/categories/{categoryId}", templateId, categoryId)).andExpect(status().isNoContent());
    }
}
