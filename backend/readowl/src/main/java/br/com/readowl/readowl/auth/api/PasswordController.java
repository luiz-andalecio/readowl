package br.com.readowl.readowl.auth.api;

import br.com.readowl.readowl.auth.application.PasswordService;
import br.com.readowl.readowl.user.application.SessionUser;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
class PasswordController {

    private final PasswordService passwords;

    PasswordController(PasswordService passwords) {
        this.passwords = passwords;
    }

    @PutMapping("/api/v1/users/me/password")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void change(@AuthenticationPrincipal SessionUser principal, @Valid @RequestBody ChangePasswordRequest body,
                HttpServletRequest request, HttpServletResponse response) {
        passwords.change(principal, body.currentPassword(), body.newPassword(), request, response);
    }
}
