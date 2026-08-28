package com.arthur.jdragresume.service;

import com.arthur.jdragresume.entity.AppUser;
import com.arthur.jdragresume.entity.JobDescription;
import com.arthur.jdragresume.entity.Resume;
import com.arthur.jdragresume.repository.AppUserRepository;
import com.arthur.jdragresume.repository.JobDescriptionRepository;
import com.arthur.jdragresume.repository.ResumeRepository;
import com.arthur.jdragresume.security.CurrentUserService;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.lang.reflect.Proxy;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

class JobSemanticMatchServiceTests {
    @Test
    void backfillsOnlyStaleVectorsThenReturnsHighestCosineCandidate() {
        AppUser user = new AppUser();
        user.setUsername("arthur");
        ReflectionTestUtils.setField(user, "id", 7L);
        Resume resume = new Resume();
        resume.setUser(user);
        resume.setTitle("Java resume");
        resume.setRawText("Java Spring Boot MySQL");
        ReflectionTestUtils.setField(resume, "id", 11L);
        JobDescription lower = job(user, 21L, "Backend", "Java");
        JobDescription higher = job(user, 22L, "RAG Backend", "Java RAG");
        AtomicReference<Resume> savedResume = new AtomicReference<>();
        AtomicReference<List<JobDescription>> savedJobs = new AtomicReference<>();

        ResumeRepository resumes = proxy(ResumeRepository.class, (ignored, method, args) -> switch (method.getName()) {
            case "findByIdAndUserId" -> Optional.of(resume);
            case "save" -> {
                savedResume.set((Resume) args[0]);
                yield args[0];
            }
            case "toString" -> "ResumeRepositorySemanticMatchDouble";
            default -> throw new UnsupportedOperationException(method.getName());
        });
        JobDescriptionRepository jobs = proxy(
                JobDescriptionRepository.class,
                (ignored, method, args) -> switch (method.getName()) {
                    case "findAllByUserId" -> List.of(lower, higher);
                    case "saveAll" -> {
                        @SuppressWarnings("unchecked")
                        List<JobDescription> values = (List<JobDescription>) args[0];
                        savedJobs.set(values);
                        yield values;
                    }
                    case "toString" -> "JobRepositorySemanticMatchDouble";
                    default -> throw new UnsupportedOperationException(method.getName());
                }
        );
        TrackingEmbeddings embeddings = new TrackingEmbeddings(resume, lower);
        JobSemanticMatchService service = new JobSemanticMatchService(
                jobs,
                resumes,
                fixedCurrentUser(user),
                embeddings
        );

        var result = service.rank(11L, 1);

        assertEquals(1, result.size());
        assertEquals(22L, result.getFirst().job().id());
        assertEquals(0.87, result.getFirst().similarity(), 0.000001);
        assertSame(resume, embeddings.refreshedResume);
        assertSame(resume, savedResume.get());
        assertEquals(List.of(lower), embeddings.refreshedJobs);
        assertEquals(List.of(lower), savedJobs.get());
    }

    private static JobDescription job(AppUser user, Long id, String title, String description) {
        JobDescription job = new JobDescription();
        job.setUser(user);
        job.setTitle(title);
        job.setCompanyName("Example");
        job.setDescription(description);
        ReflectionTestUtils.setField(job, "id", id);
        return job;
    }

    private static CurrentUserService fixedCurrentUser(AppUser user) {
        return new CurrentUserService(proxy(AppUserRepository.class, (ignored, method, args) -> null)) {
            @Override
            public AppUser getCurrentUser() {
                return user;
            }
        };
    }

    @SuppressWarnings("unchecked")
    private static <T> T proxy(Class<T> type, java.lang.reflect.InvocationHandler handler) {
        return (T) Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, handler);
    }

    private static final class TrackingEmbeddings extends SemanticEmbeddingService {
        private final Resume staleResume;
        private final JobDescription staleJob;
        private Resume refreshedResume;
        private List<JobDescription> refreshedJobs = List.of();

        private TrackingEmbeddings(Resume staleResume, JobDescription staleJob) {
            super(
                    SemanticEmbeddingTestSupport.model(text -> new float[]{1.0f, 0.0f, 0.0f}),
                    SemanticEmbeddingTestSupport.properties(3)
            );
            this.staleResume = staleResume;
            this.staleJob = staleJob;
        }

        @Override
        public boolean isCurrent(Resume resume) {
            return resume != staleResume;
        }

        @Override
        public boolean isCurrent(JobDescription job) {
            return job != staleJob;
        }

        @Override
        public void refresh(Resume resume) {
            refreshedResume = resume;
        }

        @Override
        public void refreshJobs(List<JobDescription> jobs) {
            refreshedJobs = List.copyOf(jobs);
        }

        @Override
        public double similarity(Resume resume, JobDescription job) {
            return job.getId() == 22L ? 0.87 : 0.61;
        }
    }
}
