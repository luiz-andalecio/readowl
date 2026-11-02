package br.com.readowl.readowl.user.api;

import br.com.readowl.readowl.shared.api.PageRequests;
import br.com.readowl.readowl.shared.api.PageResponse;
import br.com.readowl.readowl.user.application.AdminUserService;
import br.com.readowl.readowl.user.application.SessionUser;
import br.com.readowl.readowl.user.domain.Role;
import br.com.readowl.readowl.user.domain.UserStatus;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Set;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/admin/users")
class AdminUserController {

    private static final Set<String> SORTS = Set.of("username", "email", "createdAt");

    private final AdminUserService users;

    AdminUserController(AdminUserService users) {
        this.users = users;
    }

    /**
     * Campos ausentes não são alterados.
     */
    record UpdateUserRequest(
            @Size(max = 100) @Pattern(regexp = "(?s).*\\S.*", message = "não deve estar em branco") String name,
            @Pattern(regexp = "[A-Za-z0-9][A-Za-z0-9_-]{2,29}",
                    message = "deve ter de 3 a 30 letras, números, _ ou -, começando com letra ou número")
            String username,
            @Size(max = 2000) String description,
            Role role,
            UserStatus status) {
    }

    @GetMapping
    PageResponse<AdminUserService.AdminUserView> list(@RequestParam(required = false) @Size(max = 255) String query,
                                                      @RequestParam(defaultValue = "0") int page,
                                                      @RequestParam(defaultValue = "20") int size,
                                                      @RequestParam(defaultValue = "username") String sort) {
        return users.list(query, PageRequests.of(page, size, sort, SORTS));
    }

    @GetMapping("/{id}")
    AdminUserService.AdminUserView get(@PathVariable UUID id) {
        return users.get(id);
    }

    @PatchMapping("/{id}")
    AdminUserService.AdminUserView update(@AuthenticationPrincipal SessionUser actor, @PathVariable UUID id,
                                          @Valid @RequestBody UpdateUserRequest body) {
        return users.update(id, new AdminUserService.UserChanges(
                body.name(), body.username(), body.description(), body.role(), body.status()), actor);
    }
}
