package com.watchwise.watchwise_api.search.service;

public final class SearchViewPagination {

    public static final int MAX_PAGE = 500;

    private SearchViewPagination() {
    }

    public static int cappedTotalPages(long totalResults, int size) {
        if (totalResults <= 0) {
            return 0;
        }
        long pages = ((totalResults - 1L) / size) + 1L;
        return (int) Math.min(pages, MAX_PAGE);
    }

    public static boolean hasNext(int page, long totalResults, int size) {
        return page < cappedTotalPages(totalResults, size);
    }
}
