package com.arthur.jdragresume.common;

import com.arthur.jdragresume.exception.BusinessException;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;

public final class PageRequests {
    public static final int MAX_SIZE = 50;

    private PageRequests() {
    }

    public static PageRequest of(int page, int size, Sort sort) {
        if (page < 0) {
            throw new BusinessException("INVALID_PAGE", "page must be at least 0");
        }
        if (size < 1 || size > MAX_SIZE) {
            throw new BusinessException(
                    "INVALID_PAGE_SIZE",
                    "size must be between 1 and " + MAX_SIZE
            );
        }
        return PageRequest.of(page, size, sort);
    }
}
