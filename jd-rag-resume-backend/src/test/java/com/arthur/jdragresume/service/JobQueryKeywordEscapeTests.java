package com.arthur.jdragresume.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * 关键字按字面匹配：LIKE 通配符和转义字符本身都要被转义；空白视为不过滤。
 * SQL 层 ESCAPE '!' 是否真的生效由 JobQueryLikeEscapeMySqlTests 在真实 MySQL 上验证。
 */
class JobQueryKeywordEscapeTests {

    @Test
    void wildcardsAndEscapeCharacterAreEscaped() {
        assertEquals("!%", JobQueryService.escapeLikeKeyword("%"));
        assertEquals("C!_", JobQueryService.escapeLikeKeyword("C_"));
        assertEquals("a!!b", JobQueryService.escapeLikeKeyword("a!b"));
        assertEquals("100!% !_!!", JobQueryService.escapeLikeKeyword("100% _!"));
    }

    @Test
    void ordinaryTextIsOnlyTrimmed() {
        assertEquals("Java 后端", JobQueryService.escapeLikeKeyword("  Java 后端 "));
    }

    @Test
    void blankKeywordMeansNoFilter() {
        assertNull(JobQueryService.escapeLikeKeyword(null));
        assertNull(JobQueryService.escapeLikeKeyword(""));
        assertNull(JobQueryService.escapeLikeKeyword("   "));
    }

    @Test
    void locationIsTrimmedAndBlankMeansNoFilter() {
        assertEquals("深圳", JobQueryService.normalizeLocation(" 深圳 "));
        assertNull(JobQueryService.normalizeLocation(null));
        assertNull(JobQueryService.normalizeLocation("  "));
    }
}
