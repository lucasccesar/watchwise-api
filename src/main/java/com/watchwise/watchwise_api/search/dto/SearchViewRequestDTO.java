package com.watchwise.watchwise_api.search.dto;

import com.watchwise.watchwise_api.search.service.SearchViewScope;
import com.watchwise.watchwise_api.pickstemplate.entity.PickOrigin;
import com.watchwise.watchwise_api.userlist.dto.UserListViewItemType;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record SearchViewRequestDTO(
        @NotBlank(message = "q must not be blank")
        @Size(min = 3, message = "q must contain at least 3 characters")
        String q,
        @NotNull(message = "scope must not be null")
        SearchViewScope scope,
        @Min(value = 1, message = "page must be greater than or equal to 1")
        @Max(value = 500, message = "page must be less than or equal to 500")
        Integer page,
        @Min(value = 1, message = "size must be greater than 0")
        @Max(value = 20, message = "size must be less than or equal to 20")
        Integer size,
        PickOrigin origin,
        UserListViewItemType itemType) {

    public SearchViewRequestDTO(String q, SearchViewScope scope, Integer page, Integer size) {
        this(q, scope, page, size, null, null);
    }
}
