package com.arthur.jdragresume.service;

import com.arthur.jdragresume.dto.analysis.AnalysisHistoryRequest;
import com.arthur.jdragresume.entity.AnalysisHistory;
import com.arthur.jdragresume.entity.AnalysisStatus;
import com.arthur.jdragresume.entity.AppUser;
import com.arthur.jdragresume.entity.JobDescription;
import com.arthur.jdragresume.entity.Resume;
import com.arthur.jdragresume.repository.AnalysisHistoryRepository;
import com.arthur.jdragresume.repository.AppUserRepository;
import com.arthur.jdragresume.repository.JobDescriptionRepository;
import com.arthur.jdragresume.repository.ResumeChunkRepository;
import com.arthur.jdragresume.repository.ResumeRepository;
import com.arthur.jdragresume.security.CurrentUserService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.lang.reflect.Proxy;
import java.math.BigDecimal;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class AnalysisHistoryServiceSecurityTests {
    private RepositoryState repositoryState;
    private AnalysisHistoryService analysisHistoryService;
    private AppUser user;
    private Resume resume;
    private JobDescription jobDescription;

    @BeforeEach
    void setUp() {
        repositoryState = new RepositoryState();
        AnalysisHistoryRepository analysisHistoryRepository = proxy(
                AnalysisHistoryRepository.class,
                (ignored, method, args) -> switch (method.getName()) {
                    case "save" -> {
                        repositoryState.saved = (AnalysisHistory) args[0];
                        yield repositoryState.saved;
                    }
                    case "findByIdAndUserId" -> repositoryState.found;
                    case "toString" -> "AnalysisHistoryRepositoryTestDouble";
                    default -> throw new UnsupportedOperationException(
                            "Unexpected repository call: " + method.getName()
                    );
                }
        );

        user = new AppUser();
        user.setUsername("arthur");
        ReflectionTestUtils.setField(user, "id", 1L);
        resume = new Resume();
        resume.setTitle("Backend resume");
        ReflectionTestUtils.setField(resume, "id", 2L);
        jobDescription = new JobDescription();
        jobDescription.setTitle("Java backend engineer");
        ReflectionTestUtils.setField(jobDescription, "id", 3L);

        CurrentUserService currentUserService = new FixedCurrentUserService(user);
        analysisHistoryService = new AnalysisHistoryService(
                analysisHistoryRepository,
                currentUserService,
                new FixedResumeService(currentUserService, resume),
                new FixedJobDescriptionService(currentUserService, jobDescription)
        );
    }

    @Test
    void manualPostCannotPersistClientSuppliedScoreOrStatus() throws Exception {
        AnalysisHistoryRequest request = new ObjectMapper().readValue(
                """
                        {
                          "resumeId": 2,
                          "jobDescriptionId": 3,
                          "matchScore": 100,
                          "status": "COMPLETED",
                          "summary": "client supplied"
                        }
                        """,
                AnalysisHistoryRequest.class
        );

        analysisHistoryService.create(request);

        assertNull(repositoryState.saved.getMatchScore());
        assertEquals(AnalysisStatus.PENDING, repositoryState.saved.getStatus());
        assertEquals("client supplied", repositoryState.saved.getSummary());
    }

    @Test
    void manualUpdatePreservesAiControlledScoreAndStatus() {
        AnalysisHistory history = new AnalysisHistory();
        history.setUser(user);
        history.setResume(resume);
        history.setJobDescription(jobDescription);
        history.setMatchScore(new BigDecimal("87.50"));
        history.setStatus(AnalysisStatus.COMPLETED);
        repositoryState.found = Optional.of(history);

        analysisHistoryService.update(
                7L,
                new AnalysisHistoryRequest(2L, 3L, "edited summary")
        );

        assertEquals(new BigDecimal("87.50"), history.getMatchScore());
        assertEquals(AnalysisStatus.COMPLETED, history.getStatus());
        assertEquals("edited summary", history.getSummary());
    }

    @SuppressWarnings("unchecked")
    private static <T> T proxy(Class<T> type, java.lang.reflect.InvocationHandler handler) {
        return (T) Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, handler);
    }

    private static final class RepositoryState {
        private AnalysisHistory saved;
        private Optional<AnalysisHistory> found = Optional.empty();
    }

    private static final class FixedCurrentUserService extends CurrentUserService {
        private final AppUser user;

        private FixedCurrentUserService(AppUser user) {
            super(proxy(AppUserRepository.class, (ignored, method, args) -> {
                throw new UnsupportedOperationException("Unexpected repository call: " + method.getName());
            }));
            this.user = user;
        }

        @Override
        public AppUser getCurrentUser() {
            return user;
        }
    }

    private static final class FixedResumeService extends ResumeService {
        private final Resume resume;

        private FixedResumeService(CurrentUserService currentUserService, Resume resume) {
            super(
                    proxy(ResumeRepository.class, (ignored, method, args) -> null),
                    proxy(ResumeChunkRepository.class, (ignored, method, args) -> null),
                    currentUserService,
                    proxy(AppUserRepository.class, (ignored, method, args) -> null),
                    null,
                    "unused",
                    null
            );
            this.resume = resume;
        }

        @Override
        public Resume getEntityForCurrentUser(Long id) {
            return resume;
        }
    }

    private static final class FixedJobDescriptionService extends JobDescriptionService {
        private final JobDescription jobDescription;

        private FixedJobDescriptionService(
                CurrentUserService currentUserService,
                JobDescription jobDescription
        ) {
            super(
                    proxy(JobDescriptionRepository.class, (ignored, method, args) -> null),
                    currentUserService,
                    proxy(AppUserRepository.class, (ignored, method, args) -> null),
                    200
            );
            this.jobDescription = jobDescription;
        }

        @Override
        public JobDescription getEntityForCurrentUser(Long id) {
            return jobDescription;
        }
    }
}
