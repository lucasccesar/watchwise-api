package com.watchwise.watchwise_api.content.controller;

import com.watchwise.watchwise_api.common.security.RequestThrottler;
import com.watchwise.watchwise_api.content.dto.ContentRefCreationDTO;
import com.watchwise.watchwise_api.content.dto.ContentRefDTO;
import com.watchwise.watchwise_api.content.entity.ContentType;
import com.watchwise.watchwise_api.content.service.ContentService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ContentControllerTest {

    @Mock
    private ContentService contentService;

    @Mock
    private RequestThrottler requestThrottler;

    @InjectMocks
    private ContentController contentController;

    @BeforeEach
    void setUp() {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(UUID.randomUUID(), null, List.of())
        );
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    @DisplayName("[getOrCreateReference] Should Return Ok With ContentRefDTO - When Service Resolves The Reference")
    void shouldReturnOkWithContentRefDtoWhenServiceResolvesTheReference() {
        ContentRefCreationDTO dto = new ContentRefCreationDTO("550", ContentType.MOVIE, null, null, null);
        ContentRefDTO responseDto = new ContentRefDTO(
                UUID.randomUUID(), "550", ContentType.MOVIE, null, null, null, LocalDateTime.now(), LocalDateTime.now()
        );
        when(contentService.getOrCreateReference(dto)).thenReturn(responseDto);

        ResponseEntity<ContentRefDTO> result = contentController.getOrCreateReference(dto);

        assertThat(result.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(result.getBody()).isEqualTo(responseDto);
    }

    @Test
    @DisplayName("[getOrCreateReference] Should Delegate To Service With The Same DTO - When Called")
    void shouldDelegateToServiceWithTheSameDtoWhenCalled() {
        ContentRefCreationDTO dto = new ContentRefCreationDTO(null, ContentType.SEASON, "1399", 1, null);
        ContentRefDTO responseDto = new ContentRefDTO(
                UUID.randomUUID(), null, ContentType.SEASON, "1399", 1, null, LocalDateTime.now(), LocalDateTime.now()
        );
        when(contentService.getOrCreateReference(dto)).thenReturn(responseDto);

        contentController.getOrCreateReference(dto);

        verify(contentService).getOrCreateReference(dto);
    }

}