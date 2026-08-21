package com.arthur.jdragresume.service;

import com.arthur.jdragresume.entity.AnalysisHistory;
import com.arthur.jdragresume.entity.AnalysisStatus;
import com.arthur.jdragresume.entity.AppUser;
import com.arthur.jdragresume.entity.JobDescription;
import com.arthur.jdragresume.entity.Resume;
import com.arthur.jdragresume.exception.BusinessException;
import com.arthur.jdragresume.repository.AnalysisHistoryRepository;
import com.arthur.jdragresume.repository.AppUserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.lang.reflect.Proxy;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class AnalysisSubmitGuardTests {
    private RepositoryState state;
    private AnalysisSubmitGuard guard;
    private AppUser user;
    private Resume resume;
    private JobDescription job;

    @BeforeEach
    void setUp() {
        state = new RepositoryState();
        user = new AppUser();
        user.setUsername("arthur");
        ReflectionTestUtils.setField(user, "id", 1L);
        resume = new Resume();
        resume.setTitle("resume");
        ReflectionTestUtils.setField(resume, "id", 2L);
        job = new JobDescription();
        job.setTitle("job");
        ReflectionTestUtils.setField(job, "id", 3L);
        state.user = user;

        AnalysisHistoryRepository historyRepository = proxy(AnalysisHistoryRepository.class, (ignored, method, args) ->
                switch (method.getName()) {
                    case "existsByUser_IdAndResume_IdAndJobDescription_IdAndStatus" -> state.pendingExists;
                    case "countByUser_IdAndStatus" -> state.pendingCount;
                    case "countByUser_IdAndCreatedAtAfter" -> state.submittedCount;
                    case "save" -> {
                        state.saved = (AnalysisHistory) args[0];
                        ReflectionTestUtils.setField(state.saved, "id", 9L);
                        yield state.saved;
                    }
                    case "toString" -> "AnalysisHistoryRepositoryTestDouble";
                    default -> throw new UnsupportedOperationException(method.getName());
                });
        AppUserRepository userRepository = proxy(AppUserRepository.class, (ignored, method, args) ->
                switch (method.getName()) {
                    case "findByIdForUpdate" -> Optional.of(state.user);
                    case "toString" -> "AppUserRepositoryTestDouble";
                    default -> throw new UnsupportedOperationException(method.getName());
                });
        guard = new AnalysisSubmitGuard(historyRepository, userRepository, 2, 10, 10);
    }

    @Test
    void admitsFirstPendingAnalysis() {
        AnalysisHistory saved = guard.admit(user, resume, job);
        assertEquals(AnalysisStatus.PENDING, saved.getStatus());
        assertEquals(user, saved.getUser());
        assertEquals(9L, saved.getId());
    }

    @Test
    void rejectsDuplicatePendingTriple() {
        state.pendingExists = true;
        BusinessException exception = assertThrows(BusinessException.class, () -> guard.admit(user, resume, job));
        assertEquals("ANALYSIS_ALREADY_PENDING", exception.getCode());
    }

    @Test
    void rejectsWhenUserAlreadyHasMaxPending() {
        state.pendingCount = 2L;
        BusinessException exception = assertThrows(BusinessException.class, () -> guard.admit(user, resume, job));
        assertEquals("ANALYSIS_TOO_MANY_PENDING", exception.getCode());
    }

    @Test
    void rejectsWhenSubmitWindowIsExhausted() {
        state.submittedCount = 10L;
        BusinessException exception = assertThrows(BusinessException.class, () -> guard.admit(user, resume, job));
        assertEquals("ANALYSIS_RATE_LIMITED", exception.getCode());
    }

    @SuppressWarnings("unchecked")
    private static <T> T proxy(Class<T> type, java.lang.reflect.InvocationHandler handler) {
        return (T) Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, handler);
    }

    private static final class RepositoryState {
        private AppUser user;
        private boolean pendingExists;
        private long pendingCount;
        private long submittedCount;
        private AnalysisHistory saved;
    }
}
