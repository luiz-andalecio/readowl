package br.com.readowl.readowl.privacy.api;

import br.com.readowl.readowl.privacy.application.AccountDeletionService;
import br.com.readowl.readowl.privacy.application.PersonalDataExport;
import br.com.readowl.readowl.shared.ratelimit.RateLimitPolicy;
import br.com.readowl.readowl.shared.ratelimit.RateLimited;
import br.com.readowl.readowl.user.application.SessionUser;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Size;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;

@RestController
@RequestMapping("/api/v1/users/me")
class PrivacyController {

    private final AccountDeletionService deletion;
    private final PersonalDataExport export;

    PrivacyController(AccountDeletionService deletion, PersonalDataExport export) {
        this.deletion = deletion;
        this.export = export;
    }

    @GetMapping("/export")
    @RateLimited(RateLimitPolicy.DATA_EXPORT_ACCOUNT)
    void export(@AuthenticationPrincipal SessionUser principal, HttpServletResponse response) throws IOException {
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        response.setHeader(HttpHeaders.CONTENT_DISPOSITION,
                ContentDisposition.attachment().filename("readowl-dados.json").build().toString());
        export.write(principal.id(), response.getOutputStream());
    }

    @DeleteMapping
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void delete(@AuthenticationPrincipal SessionUser principal, @Valid @RequestBody DeleteAccountRequest body,
                HttpServletRequest request, HttpServletResponse response) {
        deletion.delete(principal, body.password(), request, response);
    }

    /**
     * @param password obrigatória para quem tem senha
     */
    record DeleteAccountRequest(@Size(max = 128) String password) {

        // Os corpos de requisição podem aparecer em logs de depuração do Spring MVC.
        @Override
        public String toString() {
            return "DeleteAccountRequest[]";
        }
    }
}
