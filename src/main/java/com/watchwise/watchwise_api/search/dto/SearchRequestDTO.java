package com.watchwise.watchwise_api.search.dto;

import com.watchwise.watchwise_api.search.service.SearchType;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record SearchRequestDTO(
        @NotBlank(message = "q must not be blank")
        @Size(min = 3, message = "q must contain at least 3 characters")
        String q,
        SearchType type,
        @Min(value = 1, message = "page must be greater than or equal to 1")
        Integer page,
        @Min(value = 1, message = "size must be greater than 0")
        @Max(value = 20, message = "size must be less than or equal to 20")
        Integer size
) {
}
