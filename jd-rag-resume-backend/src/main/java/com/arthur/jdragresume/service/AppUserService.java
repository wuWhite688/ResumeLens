package com.arthur.jdragresume.service;

import com.arthur.jdragresume.dto.user.UserRequest;
import com.arthur.jdragresume.dto.user.UserResponse;
import com.arthur.jdragresume.entity.AppUser;
import com.arthur.jdragresume.exception.ResourceNotFoundException;
import com.arthur.jdragresume.repository.AppUserRepository;
import com.arthur.jdragresume.security.CurrentUserService;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AppUserService {
    private final AppUserRepository appUserRepository;
    private final PasswordEncoder passwordEncoder;
    private final CurrentUserService currentUserService;

    public AppUserService(
            AppUserRepository appUserRepository,
            PasswordEncoder passwordEncoder,
            CurrentUserService currentUserService
    ) {
        this.appUserRepository = appUserRepository;
        this.passwordEncoder = passwordEncoder;
        this.currentUserService = currentUserService;
    }

    @Transactional(readOnly = true)
    public UserResponse findMe() {
        return UserResponse.from(currentUserService.getCurrentUser());
    }

    @Transactional
    public UserResponse updateMe(UserRequest request) {
        AppUser user = currentUserService.getCurrentUser();
        applyRequest(user, request);
        return UserResponse.from(appUserRepository.save(user));
    }

    @Transactional
    public void delete(Long id) {
        AppUser user = getEntity(id);
        appUserRepository.delete(user);
    }

    @Transactional(readOnly = true)
    public AppUser getEntity(Long id) {
        return appUserRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("user", id));
    }

    private void applyRequest(AppUser user, UserRequest request) {
        user.setUsername(request.username());
        user.setEmail(request.email());
        user.setDisplayName(request.displayName());
        user.setPasswordHash(passwordEncoder.encode(request.password()));
    }
}
