package com.arthur.jdragresume.service;

import com.arthur.jdragresume.entity.AppUser;
import com.arthur.jdragresume.entity.RefreshToken;
import com.arthur.jdragresume.exception.BusinessException;
import com.arthur.jdragresume.repository.RefreshTokenRepository;
import com.arthur.jdragresume.security.JwtProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class RefreshTokenServiceTests {
    private RepositoryState repositoryState;
    private RefreshTokenService refreshTokenService;

    @BeforeEach
    void setUp() {
        repositoryState = new RepositoryState();
        RefreshTokenRepository repository = (RefreshTokenRepository) Proxy.newProxyInstance(
                RefreshTokenRepository.class.getClassLoader(),
                new Class<?>[]{RefreshTokenRepository.class},
                repositoryState
        );
        JwtProperties properties = new JwtProperties();
        properties.setRefreshExpirationDays(7);
        refreshTokenService = new RefreshTokenService(repository, properties);
    }

    @Test
    void storesOnlyHashAndRotatesTheRefreshToken() {
        AppUser user = new AppUser();
        user.setUsername("arthur");

        RefreshTokenService.IssuedRefreshToken issued = refreshTokenService.issue(user);
        RefreshToken current = repositoryState.saved.getFirst();

        assertNotEquals(issued.value(), current.getTokenHash());
        assertEquals(64, current.getTokenHash().length());
        assertNotNull(current.getExpiresAt());

        repositoryState.saved.clear();
        repositoryState.found = Optional.of(current);
        RefreshTokenService.RotatedRefreshToken rotated = refreshTokenService.rotate(issued.value());

        assertEquals(user, rotated.user());
        assertNotEquals(issued.value(), rotated.refreshToken().value());
        assertNotNull(current.getRevokedAt());
        assertNotNull(current.getReplacedByHash());
        assertEquals(2, repositoryState.saved.size());
        assertEquals(current.getFamilyId(), repositoryState.saved.getFirst().getFamilyId());
    }

    @Test
    void reusedTokenRevokesItsActiveFamily() {
        RefreshToken reused = new RefreshToken();
        reused.setFamilyId("family-1");
        reused.setTokenHash("ignored");
        reused.setRevokedAt(LocalDateTime.now().minusSeconds(1));
        repositoryState.found = Optional.of(reused);

        BusinessException exception = assertThrows(
                BusinessException.class,
                () -> refreshTokenService.rotate("already-used-token")
        );

        assertEquals("REFRESH_TOKEN_REUSED", exception.getCode());
        assertEquals("family-1", repositoryState.revokedFamilyId);
        assertNotNull(repositoryState.familyRevokedAt);
    }

    private static final class RepositoryState implements InvocationHandler {
        private final List<RefreshToken> saved = new ArrayList<>();
        private Optional<RefreshToken> found = Optional.empty();
        private String revokedFamilyId;
        private LocalDateTime familyRevokedAt;

        @Override
        public Object invoke(Object proxy, Method method, Object[] args) {
            return switch (method.getName()) {
                case "save" -> {
                    RefreshToken token = (RefreshToken) args[0];
                    saved.add(token);
                    yield token;
                }
                case "findByTokenHashForUpdate" -> found;
                case "revokeActiveFamily" -> {
                    revokedFamilyId = (String) args[0];
                    familyRevokedAt = (LocalDateTime) args[1];
                    yield 1;
                }
                case "toString" -> "RefreshTokenRepositoryTestDouble";
                default -> throw new UnsupportedOperationException("Unexpected repository call: " + method.getName());
            };
        }
    }
}
