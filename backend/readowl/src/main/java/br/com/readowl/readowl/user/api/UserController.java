package br.com.readowl.readowl.user.api;

import br.com.readowl.readowl.media.application.AvatarImages;
import br.com.readowl.readowl.shared.api.PublicCache;
import br.com.readowl.readowl.shared.audit.SecurityEvents;
import br.com.readowl.readowl.shared.exception.ApiException;
import br.com.readowl.readowl.shared.ratelimit.RateLimitPolicy;
import br.com.readowl.readowl.shared.ratelimit.RateLimited;
import br.com.readowl.readowl.user.application.AvatarService;
import br.com.readowl.readowl.user.application.SessionUser;
import br.com.readowl.readowl.user.application.UserService;
import br.com.readowl.readowl.user.domain.ExternalLinkType;
import br.com.readowl.readowl.user.infrastructure.UserLinks;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.util.List;

@RestController
@RequestMapping("/api/v1/users")
class UserController {

    private final UserService users;
    private final SecurityEvents securityEvents;
    private final AvatarService avatars;

    UserController(UserService users, SecurityEvents securityEvents, AvatarService avatars) {
        this.avatars = avatars;
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

    /**
     * O corpo é a própria imagem, sem formulário. O limite vale mesmo sem Content-Length.
     */
    @PutMapping(value = "/me/avatar", consumes = {MediaType.IMAGE_JPEG_VALUE, MediaType.IMAGE_PNG_VALUE})
    @RateLimited(RateLimitPolicy.AVATAR_ACCOUNT)
    AvatarService.AvatarResponse uploadAvatar(@AuthenticationPrincipal SessionUser principal, HttpServletRequest request)
            throws IOException {
        byte[] file = request.getInputStream().readNBytes(AvatarImages.MAX_FILE_BYTES + 1);
        if (file.length > AvatarImages.MAX_FILE_BYTES) {
            throw ApiException.badRequest("AVATAR_FILE_TOO_LARGE", "O arquivo da foto pode ter no máximo 2 MiB.");
        }
        return avatars.upload(principal.id(), file);
    }

    @DeleteMapping("/me/avatar")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void removeAvatar(@AuthenticationPrincipal SessionUser principal) {
        avatars.remove(principal.id());
    }

    @PutMapping("/me/links")
    @RateLimited(RateLimitPolicy.PROFILE_LINKS_ACCOUNT)
    List<UserLinks.ExternalLink> replaceLinks(@AuthenticationPrincipal SessionUser principal,
                                              @Valid @RequestBody ReplaceLinksRequest body) {
        return users.replaceLinks(principal.id(), body.links().stream()
                .map(link -> new UserLinks.ExternalLink(link.type(), link.url())).toList());
    }

    record LinkRequest(@NotNull ExternalLinkType type, @NotBlank @Size(max = 1000) String url) {
    }

    record ReplaceLinksRequest(@NotNull @Size(max = 10) List<@NotNull @Valid LinkRequest> links) {
    }

    @PatchMapping("/me")
    UserService.MyProfile updateMe(@AuthenticationPrincipal SessionUser principal,
                                   @Valid @RequestBody UpdateProfileRequest body) {
        return users.updateProfile(principal.id(), body.name(), body.description(), body.emailNewChapters());
    }
}
