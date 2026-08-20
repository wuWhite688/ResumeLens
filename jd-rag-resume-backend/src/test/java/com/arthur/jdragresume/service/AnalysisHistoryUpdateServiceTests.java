package com.arthur.jdragresume.service;

import com.arthur.jdragresume.entity.AnalysisHistory;
import com.arthur.jdragresume.entity.AnalysisStatus;
import com.arthur.jdragresume.repository.AnalysisHistoryRepository;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.lang.reflect.Proxy;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AnalysisHistoryUpdateServiceTests {

    @Test
    void completeIfPendingIgnoresRowsThatAreNoLongerPending() {
        AnalysisHistory failed = new AnalysisHistory();
        ReflectionTestUtils.setField(failed, "id", 9L);
        failed.setStatus(AnalysisStatus.FAILED);
        AnalysisHistoryUpdateService service = serviceReturning(failed);

        boolean completed = service.completeIfPending(9L, history -> history.setSummary("late success"));

        assertFalse(completed);
        assertEquals(AnalysisStatus.FAILED, failed.getStatus());
        assertEquals(null, failed.getSummary());
    }

    @Test
    void completeIfPendingWritesOnlyWhilePending() {
        AnalysisHistory pending = new AnalysisHistory();
        ReflectionTestUtils.setField(pending, "id", 3L);
        pending.setStatus(AnalysisStatus.PENDING);
        AnalysisHistoryUpdateService service = serviceReturning(pending);

        boolean completed = service.completeIfPending(3L, history -> history.setSummary("done"));

        assertTrue(completed);
        assertEquals(AnalysisStatus.COMPLETED, pending.getStatus());
        assertEquals("done", pending.getSummary());
    }

    @Test
    void failIfPendingDoesNotOverwriteCompletedRows() {
        AnalysisHistory completed = new AnalysisHistory();
        ReflectionTestUtils.setField(completed, "id", 4L);
        completed.setStatus(AnalysisStatus.COMPLETED);
        completed.setSummary("ok");
        AnalysisHistoryUpdateService service = serviceReturning(completed);

        assertFalse(service.failIfPending(4L, "AI analysis failed: Timeout"));
        assertEquals(AnalysisStatus.COMPLETED, completed.getStatus());
        assertEquals("ok", completed.getSummary());
    }

    private static AnalysisHistoryUpdateService serviceReturning(AnalysisHistory history) {
        AtomicReference<AnalysisHistory> current = new AtomicReference<>(history);
        AnalysisHistoryRepository repository = proxy(AnalysisHistoryRepository.class, (ignored, method, args) -> {
            if ("findByIdForUpdate".equals(method.getName())) {
                return Optional.ofNullable(current.get());
            }
            if ("toString".equals(method.getName())) {
                return "AnalysisHistoryRepositoryTestDouble";
            }
            throw new UnsupportedOperationException(method.getName());
        });
        return new AnalysisHistoryUpdateService(repository);
    }

    @SuppressWarnings("unchecked")
    private static <T> T proxy(Class<T> type, java.lang.reflect.InvocationHandler handler) {
        return (T) Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, handler);
    }
}
