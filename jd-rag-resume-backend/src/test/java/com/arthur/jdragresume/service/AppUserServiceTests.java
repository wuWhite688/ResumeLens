package com.arthur.jdragresume.service;

import com.arthur.jdragresume.dto.user.UserRequest;
import com.arthur.jdragresume.entity.AppUser;
import com.arthur.jdragresume.exception.BusinessException;
import com.arthur.jdragresume.repository.AppUserRepository;
import com.arthur.jdragresume.security.CurrentUserService;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.lang.reflect.Proxy;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

class AppUserServiceTests {
    @Test
    void rejectsUsernameChangesSoTheCurrentAccessTokenRemainsUsable() {
        AppUser user = new AppUser();
        user.setUsername("arthur");
        user.setEmail("old@example.com");
        user.setDisplayName("Arthur");
        user.setPasswordHash("old-hash");

        AtomicBoolean saved = new AtomicBoolean();
        AppUserRepository repository = proxy(
                AppUserRepository.class,
                (ignored, method, args) -> switch (method.getName()) {
                    case "save" -> {
                        saved.set(true);
                        yield args[0];
                    }
                    case "toString" -> "AppUserRepositoryTestDouble";
                    default -> throw new UnsupportedOperationException(
                            "Unexpected repository call: " + method.getName()
                    );
                }
        );
        CurrentUserService currentUserService = new CurrentUserService(repository) {
            @Override
            public AppUser getCurrentUser() {
                return user;
            }
        };
        PasswordEncoder passwordEncoder = new PasswordEncoder() {
            @Override
            public String encode(CharSequence rawPassword) {
                return "encoded-" + rawPassword;
            }

            @Override
            public boolean matches(CharSequence rawPassword, String encodedPassword) {
                return false;
            }
        };
        AppUserService service = new AppUserService(repository, passwordEncoder, currentUserService);

        BusinessException error = assertThrows(
                BusinessException.class,
                () -> service.updateMe(new UserRequest(
                        "arthur-renamed",
                        "new@example.com",
                        "Arthur New",
                        "new-password"
                ))
        );

        assertEquals("USERNAME_IMMUTABLE", error.getCode());
        assertEquals("arthur", user.getUsername());
        assertEquals("old@example.com", user.getEmail());
        assertFalse(saved.get());
    }

    @SuppressWarnings("unchecked")
    private static <T> T proxy(Class<T> type, java.lang.reflect.InvocationHandler handler) {
        return (T) Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, handler);
    }
}
