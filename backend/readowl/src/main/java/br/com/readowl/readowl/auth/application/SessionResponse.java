package br.com.readowl.readowl.auth.application;

import br.com.readowl.readowl.user.domain.Role;

import java.util.UUID;

public record SessionResponse(boolean authenticated, User user) {

    public record User(UUID id, String username, String name, Role role, boolean hasPassword, boolean googleLinked) {
    }

    static SessionResponse anonymous() {
        return new SessionResponse(false, null);
    }
}
