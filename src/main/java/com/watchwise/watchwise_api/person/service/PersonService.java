package com.watchwise.watchwise_api.person.service;

import com.watchwise.watchwise_api.person.dto.PersonResponseDTO;
import com.watchwise.watchwise_api.person.entity.PersonParticipation;

import java.util.UUID;

public interface PersonService {

    PersonResponseDTO getPerson(
            UUID viewerId,
            String personTmdbId,
            PersonParticipation participation,
            Integer pageNumber,
            Integer pageSize);
}
