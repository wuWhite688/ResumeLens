package com.arthur.jdragresume.service;

import com.arthur.jdragresume.dto.auth.AuthResponse;
import com.arthur.jdragresume.dto.auth.LoginRequest;
import com.arthur.jdragresume.dto.auth.RegisterRequest;
import com.arthur.jdragresume.dto.user.UserResponse;
import com.arthur.jdragresume.entity.AppUser;
import com.arthur.jdragresume.exception.BusinessException;
import com.arthur.jdragresume.repository.AppUserRepository;
import com.arthur.jdragresume.security.JwtProperties;
import com.arthur.jdragresume.security.JwtService;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AuthService {
    private final AppUserRepository appUserRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;
    private final JwtProperties jwtProperties;
    private final RefreshTokenService refreshTokenService;

    public AuthService(
            AppUserRepository appUserRepository,
            PasswordEncoder passwordEncoder,
            JwtService jwtService,
            JwtProperties jwtProperties,
            RefreshTokenService refreshTokenService
    ) {
        this.appUserRepository = appUserRepository;
        this.passwordEncoder = passwordEncoder;
        this.jwtService = jwtService;
        this.jwtProperties = jwtProperties;
        this.refreshTokenService = refreshTokenService;
    }

    @Transactional
    public AuthSession register(RegisterRequest request) {
        if (appUserRepository.existsByUsername(request.username())) {
            throw new BusinessException("USERNAME_EXISTS", "username already exists");
        }
        if (appUserRepository.existsByEmail(request.email())) {
            throw new BusinessException("EMAIL_EXISTS", "email already exists");
        }

        AppUser user = new AppUser();
        user.setUsername(request.username());
        user.setEmail(request.email());
        user.setDisplayName(request.displayName());
        user.setPasswordHash(passwordEncoder.encode(request.password()));
        AppUser savedUser = appUserRepository.save(user);

        return newSession(savedUser);
    }

    @Transactional
    public AuthSession login(LoginRequest request) {
        AppUser user = appUserRepository.findByUsername(request.username())
                .orElseThrow(() -> new BadCredentialsException("username or password is incorrect"));

        if (!passwordEncoder.matches(request.password(), user.getPasswordHash())) {
            throw new BadCredentialsException("username or password is incorrect");
        }

        return newSession(user);
    }

    public AuthSession refresh(String rawRefreshToken) {
        RefreshTokenService.RotatedRefreshToken rotated = refreshTokenService.rotate(rawRefreshToken);
        return new AuthSession(buildAuthResponse(rotated.user()), rotated.refreshToken());
    }

    public void logout(String rawRefreshToken) {
        refreshTokenService.revoke(rawRefreshToken);
    }

    private AuthSession newSession(AppUser user) {
        RefreshTokenService.IssuedRefreshToken refreshToken = refreshTokenService.issue(user);
        return new AuthSession(buildAuthResponse(user), refreshToken);
    }

    private AuthResponse buildAuthResponse(AppUser user) {
        return new AuthResponse(
                "Bearer",
                jwtService.generateToken(user),
                jwtProperties.getExpirationMinutes() * 60,
                UserResponse.from(user)
        );
    }

    public record AuthSession(
            AuthResponse response,
            RefreshTokenService.IssuedRefreshToken refreshToken
    ) {
    }
}
