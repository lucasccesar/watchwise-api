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

    public PageRequest build(Integer pageNumber, Integer pageSize, String sortBy, String sortDirection) {
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
            queryPageSize = DEFAULT_PAGE_SIZE;
        } else if (pageSize > MAX_PAGE_SIZE) {
            queryPageSize = MAX_PAGE_SIZE;
        } else if (pageSize <= 0) {
            throw new BadRequestException("Page size must be greater than 0");
        } else {
            queryPageSize = pageSize;
        }

        if (sortBy == null) {
            return PageRequest.of(queryPageNumber, queryPageSize);
        }

        Sort sort = (sortDirection == null || !sortDirection.equals("desc"))
                ? Sort.by(Sort.Order.asc(sortBy))
                : Sort.by(Sort.Order.desc(sortBy));
        return PageRequest.of(queryPageNumber, queryPageSize, sort);
    }
}
