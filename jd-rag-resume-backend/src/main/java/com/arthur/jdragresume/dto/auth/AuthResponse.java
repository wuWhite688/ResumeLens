package com.arthur.jdragresume.dto.auth;

import com.arthur.jdragresume.dto.user.UserResponse;

public record AuthResponse(
        String tokenType,
        String accessToken,
        long expiresInSeconds,
        UserResponse user
) {
}
