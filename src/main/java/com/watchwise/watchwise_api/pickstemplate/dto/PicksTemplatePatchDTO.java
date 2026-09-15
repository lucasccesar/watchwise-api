package com.watchwise.watchwise_api.pickstemplate.dto;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Size;
import org.hibernate.validator.constraints.URL;
import java.time.LocalDate;

public record PicksTemplatePatchDTO(@Size(max = 120) String name, String description,
                                     @Size(max = 2048) @URL String coverImage, String instructions,
                                     LocalDate eligibilityStartDate, LocalDate eligibilityEndDate,
                                     Boolean clearEligibilityPeriod) {
    @AssertTrue(message = "eligibility dates must be both absent or ordered")
    public boolean hasValidEligibilityDatePair() {
        if (Boolean.TRUE.equals(clearEligibilityPeriod)) {
            return eligibilityStartDate == null && eligibilityEndDate == null;
        }
        return (eligibilityStartDate == null && eligibilityEndDate == null)
                || (eligibilityStartDate != null && eligibilityEndDate != null
                && !eligibilityStartDate.isAfter(eligibilityEndDate));
    }
}
