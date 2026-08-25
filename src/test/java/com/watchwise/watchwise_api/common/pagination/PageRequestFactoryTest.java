package com.watchwise.watchwise_api.common.pagination;

import com.watchwise.watchwise_api.common.exception.BadRequestException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PageRequestFactoryTest {

    private final PageRequestFactory pageRequestFactory = new PageRequestFactory();

    @Test
    @DisplayName("[build] Should Use Default Page - When Page Number Is Null")
    void shouldUseDefaultPageWhenPageNumberIsNull() {
        PageRequest pageRequest = pageRequestFactory.build(null, 10);

        assertThat(pageRequest.getPageNumber()).isEqualTo(PageRequestFactory.DEFAULT_PAGE);
    }

    @Test
    @DisplayName("[build] Should Use Default Page - When Page Number Is Zero")
    void shouldUseDefaultPageWhenPageNumberIsZero() {
        PageRequest pageRequest = pageRequestFactory.build(0, 10);

        assertThat(pageRequest.getPageNumber()).isEqualTo(PageRequestFactory.DEFAULT_PAGE);
    }

    @Test
    @DisplayName("[build] Should Use Page Number Minus One - When Page Number Is Positive")
    void shouldUsePageNumberMinusOneWhenPageNumberIsPositive() {
        PageRequest pageRequest = pageRequestFactory.build(3, 10);

        assertThat(pageRequest.getPageNumber()).isEqualTo(2);
    }

    @Test
    @DisplayName("[build] Should Throw BadRequestException - When Page Number Is Negative")
    void shouldThrowBadRequestExceptionWhenPageNumberIsNegative() {
        assertThatThrownBy(() -> pageRequestFactory.build(-1, 10))
                .isInstanceOf(BadRequestException.class)
                .hasMessage("Page number must be greater than or equal to 0");
    }

    @Test
    @DisplayName("[build] Should Use Default Page Size - When Page Size Is Null")
    void shouldUseDefaultPageSizeWhenPageSizeIsNull() {
        PageRequest pageRequest = pageRequestFactory.build(1, null);

        assertThat(pageRequest.getPageSize()).isEqualTo(PageRequestFactory.DEFAULT_PAGE_SIZE);
    }

    @Test
    @DisplayName("[build] Should Use Provided Page Size - When Page Size Is Valid")
    void shouldUseProvidedPageSizeWhenPageSizeIsValid() {
        PageRequest pageRequest = pageRequestFactory.build(1, 25);

        assertThat(pageRequest.getPageSize()).isEqualTo(25);
    }

    @Test
    @DisplayName("[build] Should Use Provided Page Size - When Page Size Is At Max Limit")
    void shouldUseProvidedPageSizeWhenPageSizeIsAtMaxLimit() {
        PageRequest pageRequest = pageRequestFactory.build(1, PageRequestFactory.MAX_PAGE_SIZE);

        assertThat(pageRequest.getPageSize()).isEqualTo(PageRequestFactory.MAX_PAGE_SIZE);
    }

    @Test
    @DisplayName("[build] Should Clamp Page Size To Max Limit - When Page Size Exceeds Limit")
    void shouldClampPageSizeToMaxLimitWhenPageSizeExceedsLimit() {
        PageRequest pageRequest = pageRequestFactory.build(1, PageRequestFactory.MAX_PAGE_SIZE + 1);

        assertThat(pageRequest.getPageSize()).isEqualTo(PageRequestFactory.MAX_PAGE_SIZE);
    }

    @Test
    @DisplayName("[build] Should Throw BadRequestException - When Page Size Is Negative")
    void shouldThrowBadRequestExceptionWhenPageSizeIsNegative() {
        assertThatThrownBy(() -> pageRequestFactory.build(1, -5))
                .isInstanceOf(BadRequestException.class)
                .hasMessage("Page size must be greater than 0");
    }

    @Test
    @DisplayName("[build] Should Throw BadRequestException - When Page Size Is Zero")
    void shouldThrowBadRequestExceptionWhenPageSizeIsZero() {
        assertThatThrownBy(() -> pageRequestFactory.build(1, 0))
                .isInstanceOf(BadRequestException.class)
                .hasMessage("Page size must be greater than 0");
    }

    @Test
    @DisplayName("[build] Should Not Apply Sort - When Sort By Is Null")
    void shouldNotApplySortWhenSortByIsNull() {
        PageRequest pageRequest = pageRequestFactory.build(1, 10, null, "desc");

        assertThat(pageRequest.getSort()).isEqualTo(Sort.unsorted());
    }

    @Test
    @DisplayName("[build] Should Apply Ascending Sort - When Sort Direction Is Null")
    void shouldApplyAscendingSortWhenSortDirectionIsNull() {
        PageRequest pageRequest = pageRequestFactory.build(1, 10, "username", null);

        assertThat(pageRequest.getSort()).isEqualTo(Sort.by(Sort.Order.asc("username")));
    }

    @Test
    @DisplayName("[build] Should Apply Ascending Sort - When Sort Direction Is Not Desc")
    void shouldApplyAscendingSortWhenSortDirectionIsNotDesc() {
        PageRequest pageRequest = pageRequestFactory.build(1, 10, "username", "asc");

        assertThat(pageRequest.getSort()).isEqualTo(Sort.by(Sort.Order.asc("username")));
    }

    @Test
    @DisplayName("[build] Should Apply Descending Sort - When Sort Direction Is Desc")
    void shouldApplyDescendingSortWhenSortDirectionIsDesc() {
        PageRequest pageRequest = pageRequestFactory.build(1, 10, "username", "desc");

        assertThat(pageRequest.getSort()).isEqualTo(Sort.by(Sort.Order.desc("username")));
    }
}
