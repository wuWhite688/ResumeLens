package com.arthur.jdragresume.exception;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import static org.junit.jupiter.api.Assertions.assertEquals;

class GlobalExceptionHandlerTests {
    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    @Test
    void mapsAuthAndAnalysisThrottlesTo429() {
        assertEquals(
                HttpStatus.TOO_MANY_REQUESTS,
                handler.handleBusiness(new BusinessException("AUTH_RATE_LIMITED", "slow down")).getStatusCode()
        );
        assertEquals(
                HttpStatus.TOO_MANY_REQUESTS,
                handler.handleBusiness(new BusinessException("ANALYSIS_RATE_LIMITED", "slow down")).getStatusCode()
        );
        assertEquals(
                HttpStatus.TOO_MANY_REQUESTS,
                handler.handleBusiness(new BusinessException("ANALYSIS_TOO_MANY_PENDING", "wait")).getStatusCode()
        );
    }

    @Test
    void mapsDuplicatePendingAnalysisTo409() {
        assertEquals(
                HttpStatus.CONFLICT,
                handler.handleBusiness(new BusinessException("ANALYSIS_ALREADY_PENDING", "running")).getStatusCode()
        );
    }
}
