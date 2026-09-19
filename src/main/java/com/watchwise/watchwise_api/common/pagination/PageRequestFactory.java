package com.watchwise.watchwise_api.common.pagination;

import com.watchwise.watchwise_api.common.exception.BadRequestException;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Component;

@Component
public class PageRequestFactory {

    public static final int DEFAULT_PAGE = 0;
    public static final int DEFAULT_PAGE_SIZE = 20;
    public static final int MAX_PAGE_SIZE = 1000;

    public PageRequest build(Integer pageNumber, Integer pageSize) {
        return build(pageNumber, pageSize, null, null);
    }

    public PageRequest build(Integer pageNumber, Integer pageSize, int maxPageSize) {
        validateMaxPageSize(maxPageSize);
        return build(pageNumber, pageSize, maxPageSize, null, null);
    }

    public PageRequest build(Integer pageNumber, Integer pageSize, String sortBy, String sortDirection) {
        return build(pageNumber, pageSize, MAX_PAGE_SIZE, sortBy, sortDirection);
    }

    private PageRequest build(Integer pageNumber, Integer pageSize, int maxPageSize, String sortBy, String sortDirection) {
        int queryPageNumber;
        int queryPageSize;

        if (pageNumber != null && pageNumber > 0) {
            queryPageNumber = pageNumber - 1;
        } else if (pageNumber == null || pageNumber == 0) {
            queryPageNumber = DEFAULT_PAGE;
        } else {
            throw new BadRequestException("Page number must be greater than or equal to 0");
        }

        if (pageSize == null) {
            queryPageSize = Math.min(DEFAULT_PAGE_SIZE, maxPageSize);
        } else if (pageSize > maxPageSize) {
            queryPageSize = maxPageSize;
        } else if (pageSize <= 0) {
            throw new BadRequestException("Page size must be greater than 0");
        } else {
            queryPageSize = pageSize;
        }

        if (sortBy == null) {
            return PageRequest.of(queryPageNumber, queryPageSize);
        }

        if (sortDirection != null && !sortDirection.equals("asc") && !sortDirection.equals("desc")) {
            throw new BadRequestException("sortDirection must be one of: asc, desc");
        }

        Sort sort = "desc".equals(sortDirection)
                ? Sort.by(Sort.Order.desc(sortBy))
                : Sort.by(Sort.Order.asc(sortBy));
        return PageRequest.of(queryPageNumber, queryPageSize, sort);
    }

    private void validateMaxPageSize(int maxPageSize) {
        if (maxPageSize <= 0 || maxPageSize > MAX_PAGE_SIZE) {
            throw new IllegalArgumentException("maxPageSize must be between 1 and " + MAX_PAGE_SIZE);
        }
    }
}
