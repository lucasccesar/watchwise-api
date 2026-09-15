package com.watchwise.watchwise_api.pickstemplate.controller;

import com.watchwise.watchwise_api.pickstemplate.service.PicksTemplateOptionService;
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

@ExtendWith(MockitoExtension.class)
class PicksTemplateOptionControllerTest {
    @Mock PicksTemplateOptionService service;
    @InjectMocks PicksTemplateOptionController controller;
    @BeforeEach void setUp() { SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(UUID.randomUUID(), null, List.of())); }
    @AfterEach void cleanUp() { SecurityContextHolder.clearContext(); }
    @Test void shouldReturnNoContentWhenDeletingOption() {
        assertThat(controller.deleteOption(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID()).getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
    }
}
