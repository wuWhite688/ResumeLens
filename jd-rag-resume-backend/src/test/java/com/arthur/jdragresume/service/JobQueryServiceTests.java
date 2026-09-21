package com.arthur.jdragresume.service;

import com.arthur.jdragresume.dto.JobListItem;
import com.arthur.jdragresume.entity.AppUser;
import com.arthur.jdragresume.exception.BusinessException;
import com.arthur.jdragresume.mapper.JobDescriptionMapper;
import com.arthur.jdragresume.security.CurrentUserService;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 回归：page * size 以前用 int 计算，超大页码会溢出。
 * 溢出有两种表现——变成负数（SQL 报错 → 500），或回绕成一个小正数（悄悄返回错页数据）。
 */
class JobQueryServiceTests {

    private final List<Long> recordedOffsets = new ArrayList<>();
    private final JobQueryService service = new JobQueryService(recordingMapper(), fixedUser());

    @Test
    void hugePageThatWouldOverflowToNegativeIsRejectedBeforeHittingTheMapper() {
        // 旧代码：Integer.MAX_VALUE * 50 在 int 下是负数 -50
        BusinessException error = assertThrows(
                BusinessException.class,
                () -> service.search(null, null, Integer.MAX_VALUE, 50)
        );

        assertEquals("PAGE_OUT_OF_RANGE", error.getCode());
        assertTrue(recordedOffsets.isEmpty(), "超大页码不应该走到 SQL");
    }

    @Test
    void hugePageThatWouldWrapAroundToASmallPositiveOffsetIsRejected() {
        // 最隐蔽的情况：(2^30 + 5) * 4 = 2^32 + 20，int 回绕后恰好是 20，
        // 旧代码会不报错地返回第 5 页的数据。
        int page = (1 << 30) + 5;
        assertEquals(20, page * 4, "前提：这组参数在 int 乘法下确实回绕成 20");

        BusinessException error = assertThrows(
                BusinessException.class,
                () -> service.search(null, null, page, 4)
        );

        assertEquals("PAGE_OUT_OF_RANGE", error.getCode());
        assertTrue(recordedOffsets.isEmpty());
    }

    @Test
    void offsetJustOverTheLimitIsRejected() {
        // 10001 * 1 = 10001 > MAX_OFFSET
        BusinessException error = assertThrows(
                BusinessException.class,
                () -> service.search(null, null, (int) JobQueryService.MAX_OFFSET + 1, 1)
        );

        assertEquals("PAGE_OUT_OF_RANGE", error.getCode());
        assertTrue(recordedOffsets.isEmpty());
    }

    @Test
    void offsetExactlyAtTheLimitIsStillAllowed() {
        // 200 * 50 = 10000 == MAX_OFFSET，边界值放行
        service.search(null, null, 200, 50);

        assertEquals(List.of(JobQueryService.MAX_OFFSET), recordedOffsets);
    }

    @Test
    void normalPagesComputeOffsetAsPageTimesSize() {
        service.search(null, null, 0, 10);
        service.search(null, null, 3, 10);

        assertEquals(List.of(0L, 30L), recordedOffsets);
    }

    @Test
    void negativePageOrNonPositiveSizeIsRejectedEvenWhenCalledWithoutTheController() {
        assertEquals("PAGE_OUT_OF_RANGE",
                assertThrows(BusinessException.class, () -> service.search(null, null, -1, 10)).getCode());
        assertEquals("PAGE_OUT_OF_RANGE",
                assertThrows(BusinessException.class, () -> service.search(null, null, 0, 0)).getCode());
        assertTrue(recordedOffsets.isEmpty());
    }

    private JobDescriptionMapper recordingMapper() {
        return (userId, keyword, location, offset, size) -> {
            recordedOffsets.add(offset);
            return List.<JobListItem>of();
        };
    }

    private static CurrentUserService fixedUser() {
        AppUser user = new AppUser();
        user.setUsername("arthur");
        return new CurrentUserService(null) {
            @Override
            public AppUser getCurrentUser() {
                return user;
            }
        };
    }
}
