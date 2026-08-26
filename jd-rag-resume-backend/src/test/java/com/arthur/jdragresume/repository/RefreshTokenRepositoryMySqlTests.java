package com.arthur.jdragresume.repository;

import com.arthur.jdragresume.entity.AppUser;
import com.arthur.jdragresume.entity.RefreshToken;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * findCleanupCandidateIds 是 JPQL，之前只有替身仓库的单元测试覆盖——那种测试不会让
 * Hibernate 解析查询，字段改名或语法出错要等到应用启动创建 EntityManagerFactory 时才暴露。
 * 这里用 @DataJpaTest 对真实 mysql:9.7.0 跑，既验证查询能被解析，也验证它选出的正是该删的行。
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Testcontainers(disabledWithoutDocker = true)
class RefreshTokenRepositoryMySqlTests {

    @Container
    private static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:9.7.0")
            .withDatabaseName("jd_rag_resume_refresh_token_test")
            .withUsername("jd_test")
            .withPassword("jd_test_password")
            .withConfigurationOverride("mysql-9.7-conf");

    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
        registry.add("spring.datasource.username", MYSQL::getUsername);
        registry.add("spring.datasource.password", MYSQL::getPassword);
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "validate");
        registry.add("spring.flyway.enabled", () -> "true");
        registry.add("app.jwt.secret", () -> "repository-test-secret-at-least-32-bytes");
    }

    @Autowired
    private AppUserRepository appUserRepository;
    @Autowired
    private RefreshTokenRepository refreshTokenRepository;

    private AppUser user;

    @BeforeEach
    void createOwner() {
        String suffix = UUID.randomUUID().toString().replace("-", "");
        user = new AppUser();
        user.setUsername("cleanup-" + suffix);
        user.setEmail("cleanup-" + suffix + "@example.com");
        user.setDisplayName("Cleanup Test");
        user.setPasswordHash("not-used");
        user = appUserRepository.saveAndFlush(user);
    }

    @Test
    void cleanupQuerySelectsExpiredAndRetiredRowsOnly() {
        LocalDateTime now = LocalDateTime.now();
        RefreshToken expired = saveToken("expired", now.minusDays(2), null);
        RefreshToken retired = saveToken("retired", now.minusHours(1), now.minusDays(8));
        RefreshToken active = saveToken("active", now.plusDays(7), null);
        RefreshToken revokedButUnexpired = saveToken("unexpired", now.plusDays(30), now.minusDays(8));
        RefreshToken recentlyRevokedAndExpired = saveToken("recently-revoked", now.minusDays(2), now.minusDays(2));

        List<Long> candidates = refreshTokenRepository.findCleanupCandidateIds(
                now.minusDays(1),
                now.minusDays(7),
                now,
                PageRequest.of(0, 500)
        );
        refreshTokenRepository.deleteAllByIdInBatch(candidates);

        assertEquals(2, candidates.size());
        assertTrue(candidates.contains(expired.getId()));
        assertTrue(candidates.contains(retired.getId()));
        assertFalse(refreshTokenRepository.existsById(expired.getId()));
        assertFalse(refreshTokenRepository.existsById(retired.getId()));
        // 未过期的一律留着，即使已经撤销——撤销行要活过整个 refresh 有效期，
        // 否则重放检测就没有可比对的记录了。
        assertTrue(refreshTokenRepository.existsById(active.getId()));
        assertTrue(refreshTokenRepository.existsById(revokedButUnexpired.getId()));
        // 已过期但刚撤销不久：仍在重放检测窗口内，不能删。
        assertTrue(refreshTokenRepository.existsById(recentlyRevokedAndExpired.getId()));
    }

    @Test
    void cleanupQueryHonorsTheRequestedBatchSize() {
        LocalDateTime now = LocalDateTime.now();
        saveToken("expired-a", now.minusDays(2), null);
        saveToken("expired-b", now.minusDays(3), null);
        saveToken("expired-c", now.minusDays(4), null);

        List<Long> firstBatch = refreshTokenRepository.findCleanupCandidateIds(
                now.minusDays(1),
                now.minusDays(7),
                now,
                PageRequest.of(0, 2)
        );

        assertEquals(2, firstBatch.size());
    }

    private RefreshToken saveToken(String label, LocalDateTime expiresAt, LocalDateTime revokedAt) {
        String suffix = UUID.randomUUID().toString().replace("-", "");
        RefreshToken token = new RefreshToken();
        token.setUser(user);
        token.setTokenHash((label + suffix).substring(0, 32));
        token.setFamilyId(UUID.randomUUID().toString());
        token.setExpiresAt(expiresAt);
        token.setRevokedAt(revokedAt);
        return refreshTokenRepository.saveAndFlush(token);
    }
}
