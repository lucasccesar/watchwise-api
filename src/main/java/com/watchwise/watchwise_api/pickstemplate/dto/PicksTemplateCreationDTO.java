package com.watchwise.watchwise_api.pickstemplate.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;
import org.hibernate.validator.constraints.URL;
import java.time.LocalDate;
import java.util.List;

public record PicksTemplateCreationDTO(@NotBlank @Size(max = 120) String name, String description,
                                        @Size(max = 2048) @URL String coverImage, String instructions,
                                        LocalDate eligibilityStartDate, LocalDate eligibilityEndDate,
                                        @NotEmpty List<@Valid PicksTemplateCategoryCreationDTO> categories) {
    @AssertTrue(message = "eligibility dates must be both absent or ordered")
    public boolean hasValidEligibilityDatePair() {
        return (eligibilityStartDate == null && eligibilityEndDate == null)
                || (eligibilityStartDate != null && eligibilityEndDate != null
                && !eligibilityStartDate.isAfter(eligibilityEndDate));
    }
}
