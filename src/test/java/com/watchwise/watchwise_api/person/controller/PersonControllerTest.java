package com.watchwise.watchwise_api.person.controller;

import com.watchwise.watchwise_api.common.dto.PageResponseDTO;
import com.watchwise.watchwise_api.person.dto.PersonResponseDTO;
import com.watchwise.watchwise_api.person.entity.PersonParticipation;
import com.watchwise.watchwise_api.person.service.PersonService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PersonControllerTest {

    @Mock
    private PersonService personService;

    @InjectMocks
    private PersonController personController;

    private UUID viewerId;

    @BeforeEach
    void setUp() {
        viewerId = UUID.randomUUID();
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(viewerId, null, List.of()));
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void shouldForwardAuthenticatedViewerAndQueryParametersAndReturnPersonResponse() {
        PersonResponseDTO expected = new PersonResponseDTO(null, null, new PageResponseDTO<>(List.of(), 0, 20, 0, 0, false));
        when(personService.getPerson(viewerId, "12345", PersonParticipation.CAST, 2, 10)).thenReturn(expected);

        ResponseEntity<PersonResponseDTO> result = personController.getPerson("12345", PersonParticipation.CAST, 2, 10);

        assertThat(result.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(result.getBody()).isSameAs(expected);
        verify(personService).getPerson(viewerId, "12345", PersonParticipation.CAST, 2, 10);
    }

    @Test
    void shouldDefaultMissingParticipationToAll() {
        PersonResponseDTO expected = new PersonResponseDTO(null, null, new PageResponseDTO<>(List.of(), 0, 20, 0, 0, false));
        when(personService.getPerson(viewerId, "12345", PersonParticipation.ALL, null, null)).thenReturn(expected);

        ResponseEntity<PersonResponseDTO> result = personController.getPerson("12345", null, null, null);

        assertThat(result.getBody()).isSameAs(expected);
        verify(personService).getPerson(viewerId, "12345", PersonParticipation.ALL, null, null);
    }
}
