package com.watchwise.watchwise_api.search.controller;

import com.watchwise.watchwise_api.common.exception.BadRequestException;
import com.watchwise.watchwise_api.search.dto.SearchRequestDTO;
import com.watchwise.watchwise_api.search.dto.SearchResultDTO;
import com.watchwise.watchwise_api.search.service.SearchService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequiredArgsConstructor
public class SearchController {

    private final SearchService searchService;

    @GetMapping("/search")
    public ResponseEntity<SearchResultDTO> search(
            @Valid @ModelAttribute SearchRequestDTO request
    ) {
        String trimmedQuery = request.q().trim();
        if (trimmedQuery.length() < 3) {
            throw new BadRequestException("q must contain at least 3 characters after trimming");
        }

        SearchResultDTO result = searchService.search(
                getCurrentUserId(), trimmedQuery, request.type(), request.page(), request.size());
        return ResponseEntity.ok(result);
    }

    private UUID getCurrentUserId() {
        return (UUID) SecurityContextHolder.getContext().getAuthentication().getPrincipal();
    }
}
