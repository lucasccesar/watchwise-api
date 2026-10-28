package com.watchwise.watchwise_api.pickstemplate.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.hibernate.validator.constraints.URL;
import java.time.LocalDate;
import java.util.List;

public record PicksTemplateCreationDTO(@NotBlank @Size(max = 120) String name,
                                        @Size(max = 5000) String description,
                                        @Size(max = 2048) @URL String coverImage,
                                        @Size(max = 10000) String instructions,
                                        LocalDate eligibilityStartDate, LocalDate eligibilityEndDate,
                                        @NotEmpty @Size(max = 50)
                                        List<@NotNull @Valid PicksTemplateCategoryCreationDTO> categories) {
    @AssertTrue(message = "eligibility dates must be both absent or ordered")
    public boolean hasValidEligibilityDatePair() {
        return (eligibilityStartDate == null && eligibilityEndDate == null)
                || (eligibilityStartDate != null && eligibilityEndDate != null
                && !eligibilityStartDate.isAfter(eligibilityEndDate));
    }

    @AssertTrue(message = "template must contain at most 200 options")
    public boolean hasAtMost200Options() {
        return categories == null || categories.stream()
                .mapToLong(category -> category == null || category.options() == null
                        ? 0 : category.options().size())
                .sum() <= 200;
    }
}
