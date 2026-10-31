package br.com.readowl.readowl.user.api;

import br.com.readowl.readowl.user.domain.User;

import java.time.Instant;

record PublicProfileResponse(String username, String name, String description, Instant createdAt) {

    static PublicProfileResponse from(User user) {
        return new PublicProfileResponse(user.getUsername(), user.getName(), user.getDescription(),
                user.getCreatedAt());
    }
}
