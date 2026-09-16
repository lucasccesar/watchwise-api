package com.watchwise.watchwise_api.pick.controller;

import com.watchwise.watchwise_api.common.exception.GlobalExceptionHandler;
import com.watchwise.watchwise_api.common.exception.TooManyRequestsException;
import com.watchwise.watchwise_api.common.security.RequestThrottler;
import com.watchwise.watchwise_api.pick.dto.PickResponseDTO;
import com.watchwise.watchwise_api.pick.dto.PickSelectionDTO;
import com.watchwise.watchwise_api.pick.entity.PickVisibility;
import com.watchwise.watchwise_api.pick.service.PickSelectionService;
import com.watchwise.watchwise_api.pick.service.PickService;
import com.watchwise.watchwise_api.pickstemplate.dto.PicksTemplatePreviewDTO;
import com.watchwise.watchwise_api.pickstemplate.entity.PickOrigin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
class PickControllerTest {
    @Mock PickService pickService;
    @Mock PickSelectionService selectionService;
    @Mock RequestThrottler requestThrottler;
    @InjectMocks PickController controller;

    MockMvc mockMvc;
    UUID currentUserId;

    @BeforeEach
    void setUp() {
        currentUserId = UUID.randomUUID();
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(currentUserId, null, List.of()));
        mockMvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @AfterEach
    void cleanUp() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void shouldMapPickRoutesToServicesAndWrapPaginatedResponses() throws Exception {
        UUID templateId = UUID.randomUUID();
        UUID pickId = UUID.randomUUID();
        UUID ownerId = UUID.randomUUID();
        UUID categoryId = UUID.randomUUID();
        PickResponseDTO response = response(pickId, templateId);
        when(pickService.createPick(eq(currentUserId), eq(templateId), any())).thenReturn(response);
        when(pickService.getMyPicks(currentUserId, templateId, 1, 20))
                .thenReturn(new PageImpl<>(List.of(response), PageRequest.of(0, 20), 1));
        when(pickService.getPick(currentUserId, pickId)).thenReturn(response);
        when(pickService.updatePick(eq(currentUserId), eq(pickId), any())).thenReturn(response);
        when(pickService.getUserPicks(currentUserId, ownerId, templateId, 1, 20))
                .thenReturn(new PageImpl<>(List.of(response), PageRequest.of(0, 20), 1));
        when(selectionService.upsertSelection(eq(currentUserId), eq(pickId), eq(categoryId), any())).thenReturn(response);

        mockMvc.perform(post("/picks-templates/{templateId}/picks", templateId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"visibility":"PUBLIC","selections":[{"categoryId":"%s","target":{"content":{"type":"MOVIE","tmdbId":"550"}}}]}
                                """.formatted(categoryId)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(pickId.toString()));
        mockMvc.perform(get("/picks-templates/{templateId}/my-picks", templateId).param("page", "1").param("size", "20"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].id").value(pickId.toString()));
        mockMvc.perform(get("/picks/{pickId}", pickId)).andExpect(status().isOk());
        mockMvc.perform(patch("/picks/{pickId}", pickId).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"visibility\":\"PRIVATE\"}"))
                .andExpect(status().isOk());
        mockMvc.perform(delete("/picks/{pickId}", pickId)).andExpect(status().isNoContent());
        mockMvc.perform(get("/users/{userId}/picks", ownerId).param("templateId", templateId.toString())
                        .param("page", "1").param("size", "20"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1));
        mockMvc.perform(put("/picks/{pickId}/categories/{categoryId}/selection", pickId, categoryId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"content\":{\"type\":\"MOVIE\",\"tmdbId\":\"550\"}}"))
                .andExpect(status().isOk());
        mockMvc.perform(delete("/picks/{pickId}/categories/{categoryId}/selection", pickId, categoryId))
                .andExpect(status().isNoContent());

        verify(pickService).deletePick(currentUserId, pickId);
        verify(selectionService).deleteSelection(currentUserId, pickId, categoryId);
    }

    @Test
    void shouldRejectTheThirdPickCreationWithoutCallingThePickService() throws Exception {
        UUID templateId = UUID.randomUUID();
        UUID categoryId = UUID.randomUUID();
        PickResponseDTO response = response(UUID.randomUUID(), templateId);
        when(pickService.createPick(eq(currentUserId), eq(templateId), any())).thenReturn(response);
        doNothing().doNothing().doThrow(new TooManyRequestsException("Too many requests. Try again later."))
                .when(requestThrottler).checkAllowed(eq("pick-create|" + currentUserId), anyInt(), any());

        String requestBody = """
                {"visibility":"PUBLIC","selections":[{"categoryId":"%s","target":{"content":{"type":"MOVIE","tmdbId":"550"}}}]}
                """.formatted(categoryId);

        mockMvc.perform(post("/picks-templates/{templateId}/picks", templateId)
                        .contentType(MediaType.APPLICATION_JSON).content(requestBody))
                .andExpect(status().isCreated());
        mockMvc.perform(post("/picks-templates/{templateId}/picks", templateId)
                        .contentType(MediaType.APPLICATION_JSON).content(requestBody))
                .andExpect(status().isCreated());
        mockMvc.perform(post("/picks-templates/{templateId}/picks", templateId)
                        .contentType(MediaType.APPLICATION_JSON).content(requestBody))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.status").value(429))
                .andExpect(jsonPath("$.path").value("/picks-templates/" + templateId + "/picks"));

        verify(requestThrottler, times(3)).checkAllowed(eq("pick-create|" + currentUserId), anyInt(), any());
        verify(pickService, times(2)).createPick(eq(currentUserId), eq(templateId), any());
    }

    @Test
    void shouldUseAnIndependentPickCreationThrottleKeyForEachAuthenticatedUser() throws Exception {
        UUID templateId = UUID.randomUUID();
        UUID categoryId = UUID.randomUUID();
        UUID secondUserId = UUID.randomUUID();
        when(pickService.createPick(any(), eq(templateId), any())).thenReturn(response(UUID.randomUUID(), templateId));

        String requestBody = """
                {"visibility":"PUBLIC","selections":[{"categoryId":"%s","target":{"content":{"type":"MOVIE","tmdbId":"550"}}}]}
                """.formatted(categoryId);

        mockMvc.perform(post("/picks-templates/{templateId}/picks", templateId)
                        .contentType(MediaType.APPLICATION_JSON).content(requestBody))
                .andExpect(status().isCreated());
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(secondUserId, null, List.of()));
        mockMvc.perform(post("/picks-templates/{templateId}/picks", templateId)
                        .contentType(MediaType.APPLICATION_JSON).content(requestBody))
                .andExpect(status().isCreated());

        verify(requestThrottler).checkAllowed(eq("pick-create|" + currentUserId), anyInt(), any());
        verify(requestThrottler).checkAllowed(eq("pick-create|" + secondUserId), anyInt(), any());
        verify(pickService, never()).getPick(any(), any());
    }

    private PickResponseDTO response(UUID pickId, UUID templateId) {
        LocalDateTime now = LocalDateTime.now();
        return new PickResponseDTO(pickId,
                new PicksTemplatePreviewDTO(templateId, PickOrigin.COMMUNITY, "Awards", null, null),
                currentUserId, PickVisibility.PUBLIC, now, now, null,
                List.of(new PickSelectionDTO(UUID.randomUUID(), UUID.randomUUID(), null, now, now, true)));
    }
}
