package com.watchwise.watchwise_api.person.controller;

import com.watchwise.watchwise_api.person.dto.PersonResponseDTO;
import com.watchwise.watchwise_api.person.entity.PersonParticipation;
import com.watchwise.watchwise_api.person.service.PersonService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.UUID;

@RestController
@RequestMapping("/people")
public class PersonController {

    private final PersonService personService;

    public PersonController(PersonService personService) {
        this.personService = personService;
    }

    @GetMapping("/{personTmdbId}")
    public ResponseEntity<PersonResponseDTO> getPerson(
            @PathVariable String personTmdbId,
            @RequestParam(required = false) PersonParticipation participation,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size) {
        UUID viewerId = (UUID) SecurityContextHolder.getContext().getAuthentication().getPrincipal();
        PersonParticipation resolvedParticipation = participation == null ? PersonParticipation.ALL : participation;
        return ResponseEntity.ok(personService.getPerson(viewerId, personTmdbId, resolvedParticipation, page, size));
    }
}
