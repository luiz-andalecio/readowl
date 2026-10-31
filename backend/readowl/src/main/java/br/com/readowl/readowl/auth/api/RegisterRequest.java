package br.com.readowl.readowl.auth.api;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

record RegisterRequest(
        @NotBlank @Size(max = 100) String name,
        @NotBlank
        @Pattern(regexp = "[A-Za-z0-9][A-Za-z0-9_-]{2,29}",
                message = "deve ter de 3 a 30 letras, números, _ ou -, começando com letra ou número")
        String username,
        @NotBlank @Email @Size(max = 255) String email,
        @NotBlank @Size(min = 8, max = 128) String password) {

    // Os corpos de requisição podem aparecer em logs de depuração do Spring MVC.
    @Override
    public String toString() {
        return "RegisterRequest[username=" + username + "]";
    }
}
