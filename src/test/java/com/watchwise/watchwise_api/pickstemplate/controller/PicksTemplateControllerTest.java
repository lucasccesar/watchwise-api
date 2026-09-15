package com.watchwise.watchwise_api.pickstemplate.controller;

import com.watchwise.watchwise_api.pickstemplate.dto.PicksTemplateCreationDTO;
import com.watchwise.watchwise_api.pickstemplate.dto.PicksTemplateResponseDTO;
import com.watchwise.watchwise_api.pickstemplate.service.PicksTemplateService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PicksTemplateControllerTest {
    @Mock PicksTemplateService service;
    @InjectMocks PicksTemplateController controller;
    UUID actorId;
    @BeforeEach void setUp() { actorId = UUID.randomUUID(); SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(actorId, null, List.of())); }
    @AfterEach void cleanUp() { SecurityContextHolder.clearContext(); }
    @Test void shouldReturnCreatedForTemplateCreation() {
        PicksTemplateCreationDTO dto = new PicksTemplateCreationDTO("Awards", null, null, null, null, null, List.of());
        PicksTemplateResponseDTO response = new PicksTemplateResponseDTO(UUID.randomUUID(), null, null, "Awards", null, null, null, null, null, null, null, List.of());
        when(service.createTemplate(actorId, dto)).thenReturn(response);
        assertThat(controller.createTemplate(dto).getStatusCode()).isEqualTo(HttpStatus.CREATED);
    }
}
