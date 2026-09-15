package com.watchwise.watchwise_api.pickstemplate.dto;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Size;
import org.hibernate.validator.constraints.URL;
import java.time.LocalDate;

public record PicksTemplatePatchDTO(@Size(max = 120) String name, String description,
                                     @Size(max = 2048) @URL String coverImage, String instructions,
                                     LocalDate eligibilityStartDate, LocalDate eligibilityEndDate) {
    @AssertTrue(message = "eligibility dates must be both absent or ordered")
    public boolean hasValidEligibilityDatePair() {
        return (eligibilityStartDate == null && eligibilityEndDate == null)
                || (eligibilityStartDate != null && eligibilityEndDate != null
                && !eligibilityStartDate.isAfter(eligibilityEndDate));
    }
}
