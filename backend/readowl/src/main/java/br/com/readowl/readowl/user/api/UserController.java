package br.com.readowl.readowl.user.api;

import br.com.readowl.readowl.user.application.SessionUser;
import br.com.readowl.readowl.user.application.UserService;
import jakarta.validation.Valid;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/users")
class UserController {

    private final UserService users;

    UserController(UserService users) {
        this.users = users;
    }

    @GetMapping("/{username}")
    PublicProfileResponse publicProfile(@PathVariable String username) {
        return PublicProfileResponse.from(users.requirePublicProfile(username));
    }

    @GetMapping("/me")
    MyProfileResponse me(@AuthenticationPrincipal SessionUser principal) {
        return MyProfileResponse.from(users.requireById(principal.id()));
    }

    @PatchMapping("/me")
    MyProfileResponse updateMe(@AuthenticationPrincipal SessionUser principal,
                               @Valid @RequestBody UpdateProfileRequest body) {
        return MyProfileResponse.from(users.updateProfile(principal.id(), body.name(), body.description()));
    }
}
