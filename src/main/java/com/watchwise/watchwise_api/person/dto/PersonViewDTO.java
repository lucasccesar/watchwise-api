package com.watchwise.watchwise_api.person.dto;

import com.watchwise.watchwise_api.common.dto.PageResponseDTO;

public record PersonViewDTO(
        PersonDetailsDTO person,
        Long followersCount,
        PersonHeroDTO hero,
        PersonProgressDTO progress,
        PageResponseDTO<PersonCreditViewDTO> credits) {
}
