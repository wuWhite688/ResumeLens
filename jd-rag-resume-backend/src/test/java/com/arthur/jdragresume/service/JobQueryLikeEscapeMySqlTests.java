package com.arthur.jdragresume.service;

import com.arthur.jdragresume.dto.JobListItem;
import com.arthur.jdragresume.entity.AppUser;
import com.arthur.jdragresume.mapper.JobDescriptionMapper;
import com.arthur.jdragresume.repository.AppUserRepository;
import com.arthur.jdragresume.security.CurrentUserService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.boot.autoconfigure.MybatisAutoConfiguration;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 真实 MySQL + 真实 MyBatis XML：用户输入的 %、_、! 必须按字面匹配，
 * 而不是被 LIKE 当成通配符。只测 service 的转义函数覆盖不到 XML 里的 ESCAPE 子句。
 */
@DataJpaTest
@ImportAutoConfiguration(MybatisAutoConfiguration.class)
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Testcontainers(disabledWithoutDocker = true)
class JobQueryLikeEscapeMySqlTests {

    @Container
    private static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:9.7.0")
            .withDatabaseName("jd_rag_resume_like_escape_test")
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
    private JobDescriptionMapper jobDescriptionMapper;
    @Autowired
    private AppUserRepository appUserRepository;
    @Autowired
    private JdbcTemplate jdbcTemplate;

    private JobQueryService service;

    @BeforeEach
    void setUp() {
        AppUser owner = saveUser();
        service = new JobQueryService(jobDescriptionMapper, fixedUser(owner));
        LocalDateTime at = LocalDateTime.of(2026, 9, 5, 9, 0);
        for (String title : List.of("C_Lang", "CXLang", "100% remote", "100 remote", "a!b", "ab")) {
            insertJob(owner, title, at);
        }
    }

    @Test
    void percentMatchesOnlyTitlesContainingALiteralPercent() {
        assertEquals(Set.of("100% remote"), titles(service.search("%", null, 0, 50)));
    }

    @Test
    void underscoreMatchesOnlyALiteralUnderscore() {
        assertEquals(Set.of("C_Lang"), titles(service.search("C_", null, 0, 50)));
    }

    @Test
    void escapeCharacterItselfIsMatchedLiterally() {
        assertEquals(Set.of("a!b"), titles(service.search("!", null, 0, 50)));
    }

    @Test
    void blankKeywordDoesNotFilter() {
        assertEquals(6, service.search("   ", null, 0, 50).size());
    }

    private static Set<String> titles(List<JobListItem> items) {
        return items.stream().map(JobListItem::getTitle).collect(Collectors.toSet());
    }

    private AppUser saveUser() {
        String suffix = UUID.randomUUID().toString().replace("-", "");
        AppUser user = new AppUser();
        user.setUsername("like-escape-" + suffix);
        user.setEmail("like-escape-" + suffix + "@example.com");
        user.setDisplayName("Like Escape Test");
        user.setPasswordHash("not-used");
        return appUserRepository.saveAndFlush(user);
    }

    private void insertJob(AppUser user, String title, LocalDateTime createdAt) {
        Timestamp timestamp = Timestamp.valueOf(createdAt);
        jdbcTemplate.update(
                "INSERT INTO job_description (created_at, updated_at, company_name, description, title, user_id) "
                        + "VALUES (?, ?, ?, ?, ?, ?)",
                timestamp, timestamp, "Test Co", "JD body", title, user.getId());
    }

    private static CurrentUserService fixedUser(AppUser user) {
        return new CurrentUserService(null) {
            @Override
            public AppUser getCurrentUser() {
                return user;
            }
        };
    }
}
