package com.watchwise.watchwise_api.person.dto;

import com.watchwise.watchwise_api.common.dto.PageResponseDTO;

public record PersonResponseDTO(
        PersonDetailsDTO person,
        PersonProgressDTO progress,
        PageResponseDTO<PersonCreditDTO> credits) {
}
