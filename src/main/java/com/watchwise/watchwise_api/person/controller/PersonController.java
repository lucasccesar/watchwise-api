package com.watchwise.watchwise_api.person.controller;

import com.watchwise.watchwise_api.person.dto.PersonResponseDTO;
import com.watchwise.watchwise_api.person.dto.PersonCreditSort;
import com.watchwise.watchwise_api.person.dto.PersonCreditSortDirection;
import com.watchwise.watchwise_api.person.dto.PersonViewDTO;
import com.watchwise.watchwise_api.person.entity.PersonParticipation;
import com.watchwise.watchwise_api.person.service.PersonViewService;
import com.watchwise.watchwise_api.person.service.PersonService;
import com.watchwise.watchwise_api.content.dto.WatchStatus;
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
    private final PersonViewService personViewService;

    public PersonController(PersonService personService, PersonViewService personViewService) {
        this.personService = personService;
        this.personViewService = personViewService;
    }

    @GetMapping("/{personTmdbId}")
    public ResponseEntity<PersonResponseDTO> getPerson(
            @PathVariable String personTmdbId,
            @RequestParam(required = false) PersonParticipation participation,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size) {
        PersonParticipation resolvedParticipation = participation == null ? PersonParticipation.ALL : participation;
        return ResponseEntity.ok(personService.getPerson(currentUserId(), personTmdbId, resolvedParticipation, page, size));
    }

    @GetMapping("/{personTmdbId}/view")
    public ResponseEntity<PersonViewDTO> getPersonView(
            @PathVariable String personTmdbId,
            @RequestParam(required = false) PersonParticipation participation,
            @RequestParam(required = false) WatchStatus watchStatus,
            @RequestParam(required = false) Boolean inWatchlist,
            @RequestParam(required = false) PersonCreditSort sortBy,
            @RequestParam(required = false) PersonCreditSortDirection sortDirection,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size) {
        return ResponseEntity.ok(personViewService.getPersonView(currentUserId(), personTmdbId, participation,
                watchStatus, inWatchlist, sortBy, sortDirection, page, size));
    }

    private UUID currentUserId() {
        return (UUID) SecurityContextHolder.getContext().getAuthentication().getPrincipal();
    }
}
