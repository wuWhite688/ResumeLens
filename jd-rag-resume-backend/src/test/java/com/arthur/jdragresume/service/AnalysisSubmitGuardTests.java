package com.arthur.jdragresume.service;

import com.arthur.jdragresume.entity.AnalysisHistory;
import com.arthur.jdragresume.entity.AnalysisStatus;
import com.arthur.jdragresume.entity.AppUser;
import com.arthur.jdragresume.entity.JobDescription;
import com.arthur.jdragresume.entity.Resume;
import com.arthur.jdragresume.exception.BusinessException;
import com.arthur.jdragresume.repository.AnalysisHistoryRepository;
import com.arthur.jdragresume.repository.AppUserRepository;
import com.arthur.jdragresume.repository.JobDescriptionRepository;
import com.arthur.jdragresume.repository.ResumeRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.lang.reflect.Proxy;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
        resume.setRawText("Java Spring Boot");
        ReflectionTestUtils.setField(resume, "id", 2L);
        job = new JobDescription();
        job.setTitle("job");
        job.setDescription("Build backend services");
        job.setRequirements("Java");
        ReflectionTestUtils.setField(job, "id", 3L);
        state.user = user;

        AnalysisHistoryRepository historyRepository = proxy(AnalysisHistoryRepository.class, (ignored, method, args) ->
                switch (method.getName()) {
                    case "findFirstByUser_IdAndResume_IdAndJobDescription_IdAndStatusAndResumeFingerprintAndJobFingerprintOrderByIdDesc" ->
                            Optional.ofNullable(state.existingPending);
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
        ResumeRepository resumeRepository = proxy(ResumeRepository.class, (ignored, method, args) ->
                switch (method.getName()) {
                    case "findByIdAndUserId" -> Optional.of(resume);
                    case "toString" -> "ResumeRepositoryTestDouble";
                    default -> throw new UnsupportedOperationException(method.getName());
                });
        JobDescriptionRepository jobDescriptionRepository = proxy(JobDescriptionRepository.class, (ignored, method, args) ->
                switch (method.getName()) {
                    case "findByIdAndUserId" -> Optional.of(job);
                    case "toString" -> "JobDescriptionRepositoryTestDouble";
                    default -> throw new UnsupportedOperationException(method.getName());
                });
        guard = new AnalysisSubmitGuard(
                historyRepository,
                userRepository,
                resumeRepository,
                jobDescriptionRepository,
                2,
                10,
                10
        );
    }

    @Test
    void admitsFirstPendingAnalysis() {
        AnalysisSubmitGuard.Admission admission = guard.admit(user, resume.getId(), job.getId());
        AnalysisHistory saved = admission.history();
        assertFalse(admission.reusedPending());
        assertEquals(AnalysisStatus.PENDING, saved.getStatus());
        assertEquals(user, saved.getUser());
        assertEquals(9L, saved.getId());
        assertEquals(ContentFingerprints.resume(resume), saved.getResumeFingerprint());
        assertEquals(ContentFingerprints.job(job), saved.getJobFingerprint());
    }

    @Test
    void reusesPendingAnalysisForTheSameInputVersion() {
        state.existingPending = new AnalysisHistory();
        ReflectionTestUtils.setField(state.existingPending, "id", 8L);

        AnalysisSubmitGuard.Admission admission = guard.admit(user, resume.getId(), job.getId());

        assertTrue(admission.reusedPending());
        assertSame(state.existingPending, admission.history());
    }

    @Test
    void rejectsWhenUserAlreadyHasMaxPending() {
        state.pendingCount = 2L;
        BusinessException exception = assertThrows(
                BusinessException.class,
                () -> guard.admit(user, resume.getId(), job.getId())
        );
        assertEquals("ANALYSIS_TOO_MANY_PENDING", exception.getCode());
    }

    @Test
    void rejectsWhenSubmitWindowIsExhausted() {
        state.submittedCount = 10L;
        BusinessException exception = assertThrows(
                BusinessException.class,
                () -> guard.admit(user, resume.getId(), job.getId())
        );
        assertEquals("ANALYSIS_RATE_LIMITED", exception.getCode());
    }

    @SuppressWarnings("unchecked")
    private static <T> T proxy(Class<T> type, java.lang.reflect.InvocationHandler handler) {
        return (T) Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, handler);
    }

    private static final class RepositoryState {
        private AppUser user;
        private AnalysisHistory existingPending;
        private long pendingCount;
        private long submittedCount;
        private AnalysisHistory saved;
    }
}
