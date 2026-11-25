package com.watchwise.watchwise_api.pick.dto;

import com.watchwise.watchwise_api.pick.entity.PickVisibility;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

public record PickListCardDTO(
        UUID id, Template picksTemplate, PickVisibility visibility, LocalDateTime createdAt,
        long answeredCategoriesCount, long totalCategoriesCount, List<AnsweredCategory> answeredCategories,
        int likesCount, long commentsCount) {

    public record Template(String name, String coverImage, Creator creator) {
    }

    public record Creator(String username, String name, String profilePicture) {
    }

    public record AnsweredCategory(UUID categoryId, String name) {
    }
}
