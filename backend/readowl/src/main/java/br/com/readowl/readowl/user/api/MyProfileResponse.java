package br.com.readowl.readowl.user.api;

import br.com.readowl.readowl.user.domain.Role;
import br.com.readowl.readowl.user.domain.User;

import java.time.Instant;
import java.util.UUID;

record MyProfileResponse(UUID id, String username, String name, String email, String description, Role role,
                         boolean emailVerified, Instant createdAt) {

    static MyProfileResponse from(User user) {
        return new MyProfileResponse(user.getId(), user.getUsername(), user.getName(), user.getEmail(),
                user.getDescription(), user.getRole(), user.isEmailVerified(), user.getCreatedAt());
    }
}
