package br.com.readowl.readowl.user.infrastructure;

import br.com.readowl.readowl.user.domain.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.Optional;
import java.util.UUID;

public interface UserRepository extends JpaRepository<User, UUID> {

    /**
     * Compara com lower(email) para usar o índice único ux_users_email_lower.
     */
    @Query("select u from User u where lower(u.email) = :email")
    Optional<User> findByNormalizedEmail(String email);

    Optional<User> findByUsername(String username);

    boolean existsByUsername(String username);
}
