package com.watchwise.watchwise_api.platformimport.model;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.watchwise.watchwise_api.content.dto.MediaReference;
import com.watchwise.watchwise_api.content.entity.ContentType;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;
import java.util.UUID;

@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "operation")
@JsonSubTypes({
        @JsonSubTypes.Type(value = ImportResolutionOperation.ResolveTarget.class, name = "RESOLVE_TARGET"),
        @JsonSubTypes.Type(value = ImportResolutionOperation.SkipItem.class, name = "SKIP_ITEM"),
        @JsonSubTypes.Type(value = ImportResolutionOperation.EditField.class, name = "EDIT_FIELD"),
        @JsonSubTypes.Type(value = ImportResolutionOperation.AssignPass.class, name = "ASSIGN_PASS"),
        @JsonSubTypes.Type(value = ImportResolutionOperation.ConfirmWatchScope.class, name = "CONFIRM_WATCH_SCOPE"),
        @JsonSubTypes.Type(value = ImportResolutionOperation.LinkImportedList.class, name = "LINK_IMPORTED_LIST"),
        @JsonSubTypes.Type(value = ImportResolutionOperation.ResolveReviewOrder.class, name = "RESOLVE_REVIEW_ORDER"),
        @JsonSubTypes.Type(value = ImportResolutionOperation.RemoveNominee.class, name = "REMOVE_NOMINEE"),
        @JsonSubTypes.Type(value = ImportResolutionOperation.RemoveCategory.class, name = "REMOVE_CATEGORY"),
        @JsonSubTypes.Type(value = ImportResolutionOperation.SetCategoryOpen.class, name = "SET_CATEGORY_OPEN")
})
public sealed interface ImportResolutionOperation permits
        ImportResolutionOperation.ResolveTarget,
        ImportResolutionOperation.SkipItem,
        ImportResolutionOperation.EditField,
        ImportResolutionOperation.AssignPass,
        ImportResolutionOperation.ConfirmWatchScope,
        ImportResolutionOperation.LinkImportedList,
        ImportResolutionOperation.ResolveReviewOrder,
        ImportResolutionOperation.RemoveNominee,
        ImportResolutionOperation.RemoveCategory,
        ImportResolutionOperation.SetCategoryOpen {

    UUID itemId();

    record ResolveTarget(@NotNull UUID itemId, String candidateId, ImportTarget target)
            implements ImportResolutionOperation {
        public ResolveTarget(UUID itemId, ImportTarget target) {
            this(itemId, null, target);
        }

        public ResolveTarget(UUID itemId, String candidateId) {
            this(itemId, candidateId, null);
        }

        public ResolveTarget {
            if (itemId == null || ((candidateId == null || candidateId.isBlank()) == (target == null))) {
                throw new IllegalArgumentException("Exactly one target candidate or explicit target is required");
            }
        }
    }

    record SkipItem(@NotNull UUID itemId) implements ImportResolutionOperation {
    }

    record EditField(@NotNull UUID itemId, @NotNull EditableField field,
                     @NotNull @Size(max = 1_048_576) String value) implements ImportResolutionOperation {
    }

    record AssignPass(@NotNull UUID itemId, @Min(1) int passNumber) implements ImportResolutionOperation {
    }

    record ConfirmWatchScope(@NotNull UUID itemId, @NotNull WatchScope scope,
                             @NotNull List<MediaReference> episodeReferences) implements ImportResolutionOperation {
        public ConfirmWatchScope {
            if (scope == null || episodeReferences == null) {
                throw new IllegalArgumentException("Watch scope and episode references are required");
            }
            episodeReferences = List.copyOf(episodeReferences);
            if (scope == WatchScope.ALL_RELEASED_EPISODES && !episodeReferences.isEmpty()) {
                throw new IllegalArgumentException("All-released scope cannot include explicit episode references");
            }
            if (scope == WatchScope.SPECIFIC_EPISODES
                    && (episodeReferences.isEmpty() || episodeReferences.stream()
                    .anyMatch(reference -> reference == null || reference.type() != ContentType.EPISODE))) {
                throw new IllegalArgumentException("Specific watch scope requires episode references");
            }
        }
    }

    record LinkImportedList(@NotNull UUID itemId, @NotNull UUID targetListId) implements ImportResolutionOperation {
    }

    record ResolveReviewOrder(@NotNull UUID itemId, @NotNull UUID beforeItemId) implements ImportResolutionOperation {
        public ResolveReviewOrder {
            if (itemId != null && itemId.equals(beforeItemId)) {
                throw new IllegalArgumentException("A review cannot be ordered before itself");
            }
        }
    }

    record RemoveNominee(@NotNull UUID itemId) implements ImportResolutionOperation {
    }

    record RemoveCategory(@NotNull UUID itemId) implements ImportResolutionOperation {
    }

    record SetCategoryOpen(@NotNull UUID itemId, boolean open) implements ImportResolutionOperation {
    }

    enum WatchScope { ALL_RELEASED_EPISODES, SPECIFIC_EPISODES }

    enum EditableField {
        TITLE, DESCRIPTION, RELEASE_YEAR, RATING, REVIEW, WATCHED_DATE, WATCHED_IN_THEATER,
        COVER_IMAGE, ELIGIBILITY_START, ELIGIBILITY_END, CATEGORY_NAME
    }
}
