package com.watchwise.watchwise_api.person.service;

import com.watchwise.watchwise_api.content.dto.WatchStatus;
import com.watchwise.watchwise_api.person.dto.PersonCreditSort;
import com.watchwise.watchwise_api.person.dto.PersonCreditSortDirection;
import com.watchwise.watchwise_api.person.dto.PersonViewDTO;
import com.watchwise.watchwise_api.person.entity.PersonParticipation;

import java.util.UUID;

public interface PersonViewService {

    PersonViewDTO getPersonView(
            UUID viewerId,
            String personTmdbId,
            PersonParticipation participation,
            WatchStatus watchStatus,
            Boolean inWatchlist,
            PersonCreditSort sortBy,
            PersonCreditSortDirection sortDirection,
            Integer page,
            Integer size);
}
