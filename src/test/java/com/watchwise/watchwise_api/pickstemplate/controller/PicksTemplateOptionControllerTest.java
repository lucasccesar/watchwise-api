package com.watchwise.watchwise_api.pickstemplate.controller;

import com.watchwise.watchwise_api.pick.dto.PickOptionSearchDTO;
import com.watchwise.watchwise_api.pickstemplate.dto.PicksTemplateOptionDTO;
import com.watchwise.watchwise_api.pickstemplate.service.PicksTemplateOptionService;
import com.watchwise.watchwise_api.common.exception.GlobalExceptionHandler;
import com.watchwise.watchwise_api.common.exception.BadRequestException;
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
import org.springframework.data.domain.Page;
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
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;

@ExtendWith(MockitoExtension.class)
class PicksTemplateOptionControllerTest {
    @Mock PicksTemplateOptionService service;
    @InjectMocks PicksTemplateOptionController controller;
    MockMvc mockMvc;
    UUID actorId;
    @BeforeEach void setUp() { actorId = UUID.randomUUID(); SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(actorId, null, List.of())); mockMvc = MockMvcBuilders.standaloneSetup(controller).setControllerAdvice(new GlobalExceptionHandler()).build(); }
    @AfterEach void cleanUp() { SecurityContextHolder.clearContext(); }
    @Test void shouldMapAllOptionRoutesThroughMvcAndBindQ() throws Exception {
        UUID templateId = UUID.randomUUID();
        UUID categoryId = UUID.randomUUID();
        UUID optionId = UUID.randomUUID();
        when(service.addOption(eq(actorId), eq(templateId), eq(categoryId), any())).thenReturn(new PicksTemplateOptionDTO(optionId, null, null));
        when(service.searchOptions(actorId, templateId, categoryId, "Ada", null, null, 2, 40))
                .thenReturn(tmdbMetadataPage());

        mockMvc.perform(post("/picks-templates/{templateId}/categories/{categoryId}/options", templateId, categoryId)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"target\":{\"content\":{\"type\":\"MOVIE\",\"tmdbId\":\"550\"}}}")).andExpect(status().isCreated());
        mockMvc.perform(get("/picks-templates/{templateId}/categories/{categoryId}/options", templateId, categoryId)
                        .param("q", "Ada").param("page", "2").param("size", "40"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.page").value(2))
                .andExpect(jsonPath("$.size").value(40))
                .andExpect(jsonPath("$.totalElements").value(81))
                .andExpect(jsonPath("$.totalPages").value(5))
                .andExpect(jsonPath("$.hasNext").value(true));
        mockMvc.perform(delete("/picks-templates/{templateId}/categories/{categoryId}/options/{optionId}", templateId, categoryId, optionId)).andExpect(status().isNoContent());
        verify(service).searchOptions(actorId, templateId, categoryId, "Ada", null, null, 2, 40);
    }

    private Page<PickOptionSearchDTO> tmdbMetadataPage() {
        PageRequest requestedPage = PageRequest.of(1, 40);
        return new PageImpl<>(List.of(), requestedPage, 81) {
            @Override
            public int getNumber() {
                return 1;
            }

            @Override
            public int getTotalPages() {
                return 5;
            }

            @Override
            public boolean hasNext() {
                return true;
            }
        };
    }

    @Test void shouldReturnBadRequestForMissingOrBlankOpenSearchQuery() throws Exception {
        UUID templateId = UUID.randomUUID();
        UUID categoryId = UUID.randomUUID();
        when(service.searchOptions(eq(actorId), eq(templateId), eq(categoryId), isNull(), isNull(), isNull(), isNull(), isNull()))
                .thenThrow(new BadRequestException("q must be provided"));
        when(service.searchOptions(eq(actorId), eq(templateId), eq(categoryId), eq("   "), isNull(), isNull(), isNull(), isNull()))
                .thenThrow(new BadRequestException("q must be provided"));

        mockMvc.perform(get("/picks-templates/{templateId}/categories/{categoryId}/options", templateId, categoryId))
                .andExpect(status().isBadRequest());
        mockMvc.perform(get("/picks-templates/{templateId}/categories/{categoryId}/options", templateId, categoryId).param("q", "   "))
                .andExpect(status().isBadRequest());
    }

}
