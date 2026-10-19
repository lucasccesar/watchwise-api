package com.watchwise.watchwise_api.search.dto;

import com.watchwise.watchwise_api.search.service.SearchViewScope;
import com.watchwise.watchwise_api.pickstemplate.entity.PickOrigin;
import com.watchwise.watchwise_api.userlist.dto.UserListViewItemType;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class SearchViewRequestDTOTest {

    private final Validator validator = Validation.buildDefaultValidatorFactory().getValidator();

    @Test
    @DisplayName("[validate] Should Reject Size Above Twenty - When Size Exceeds Maximum")
    void shouldRejectSizeAboveTwentyWhenSizeExceedsMaximum() {
        Set<ConstraintViolation<SearchViewRequestDTO>> violations = validator.validate(
                new SearchViewRequestDTO("night", SearchViewScope.CONTENT, 1, 21));

        assertThat(violations).extracting(ConstraintViolation::getMessage)
                .contains("size must be less than or equal to 20");
    }

    @Test
    @DisplayName("[validate] Should Require Visual Scope - When Scope Is Missing")
    void shouldRequireVisualScopeWhenScopeIsMissing() {
        Set<ConstraintViolation<SearchViewRequestDTO>> violations = validator.validate(
                new SearchViewRequestDTO("night", null, 1, 18));

        assertThat(violations).extracting(ConstraintViolation::getMessage)
                .contains("scope must not be null");
    }

    @Test
    @DisplayName("[validate] Should Accept One Hundred Trimmed Query Characters - When Surrounding Whitespace Is Present And Page Is Minimum")
    void shouldAcceptOneHundredTrimmedQueryCharactersWhenSurroundingWhitespaceIsPresentAndPageIsMinimum() {
        SearchViewRequestDTO request = new SearchViewRequestDTO(
                " " + "a".repeat(100) + " ", SearchViewScope.CONTENT, 1, 18);

        assertThat(validator.validate(request)).isEmpty();
    }

    @Test
    void shouldAcceptPicksTemplateOriginAndListItemTypeAsBindableFilters() {
        assertThat(validator.validate(new SearchViewRequestDTO(
                "night", SearchViewScope.PICKS_TEMPLATE, 1, 10, PickOrigin.COMMUNITY, null))).isEmpty();
        assertThat(validator.validate(new SearchViewRequestDTO(
                "night", SearchViewScope.LIST, 1, 10, null, UserListViewItemType.MOVIE))).isEmpty();
    }
}
