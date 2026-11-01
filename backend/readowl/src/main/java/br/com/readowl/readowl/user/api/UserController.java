package br.com.readowl.readowl.user.api;

import br.com.readowl.readowl.shared.api.PublicCache;
import br.com.readowl.readowl.shared.audit.SecurityEvents;
import br.com.readowl.readowl.user.application.SessionUser;
import br.com.readowl.readowl.user.application.UserService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/users")
class UserController {

    private final UserService users;
    private final SecurityEvents securityEvents;

    UserController(UserService users, SecurityEvents securityEvents) {
        this.users = users;
        this.securityEvents = securityEvents;
    }

    @GetMapping("/{username}")
    @PublicCache
    UserService.PublicProfile publicProfile(@PathVariable String username) {
        return users.publicProfile(username);
    }

    @GetMapping("/me")
    UserService.MyProfile me(@AuthenticationPrincipal SessionUser principal) {
        return users.myProfile(principal.id());
    }

    /**
     * Quem entrou pelo Google, ou quem tinha aceitado uma versão anterior, aceita aqui.
     */
    @PostMapping("/me/terms")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void acceptTerms(@AuthenticationPrincipal SessionUser principal, @Valid @RequestBody AcceptTermsRequest body) {
        users.acceptTerms(principal.id(), body.termsVersion());
        securityEvents.record(SecurityEvents.Event.TERMS_ACCEPTED, principal.id());
    }

    record AcceptTermsRequest(@NotBlank @Size(max = 20) String termsVersion,
                              @NotNull @AssertTrue(message = "é preciso declarar a idade mínima") Boolean ageConfirmed) {
    }

    @PatchMapping("/me")
    UserService.MyProfile updateMe(@AuthenticationPrincipal SessionUser principal,
                                   @Valid @RequestBody UpdateProfileRequest body) {
        return users.updateProfile(principal.id(), body.name(), body.description(), body.emailNewChapters());
    }
}
