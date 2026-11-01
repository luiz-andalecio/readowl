package br.com.readowl.readowl.auth.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * @param rememberMe mantém a pessoa conectada depois de fechar o navegador; ausente, não mantém
 */
record LoginRequest(@NotBlank @Size(max = 255) String email, @NotBlank @Size(max = 128) String password,
                    Boolean rememberMe) {

    // Os corpos de requisição podem aparecer em logs de depuração do Spring MVC.
    @Override
    public String toString() {
        return "LoginRequest[]";
    }
}
