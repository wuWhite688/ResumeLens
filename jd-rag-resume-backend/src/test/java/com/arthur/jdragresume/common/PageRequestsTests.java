package com.arthur.jdragresume.common;

import com.arthur.jdragresume.exception.BusinessException;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Sort;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class PageRequestsTests {
    @Test
    void acceptsSizeWithinLimit() {
        var page = PageRequests.of(0, 50, Sort.by("createdAt"));
        assertEquals(50, page.getPageSize());
        assertEquals(0, page.getPageNumber());
    }

    @Test
    void rejectsSizeAboveLimit() {
        BusinessException exception = assertThrows(
                BusinessException.class,
                () -> PageRequests.of(0, 51, Sort.unsorted())
        );
        assertEquals("INVALID_PAGE_SIZE", exception.getCode());
    }

    @Test
    void rejectsNegativePage() {
        BusinessException exception = assertThrows(
                BusinessException.class,
                () -> PageRequests.of(-1, 10, Sort.unsorted())
        );
        assertEquals("INVALID_PAGE", exception.getCode());
    }
}
