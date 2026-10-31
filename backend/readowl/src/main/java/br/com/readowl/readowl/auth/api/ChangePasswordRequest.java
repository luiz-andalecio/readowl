package br.com.readowl.readowl.auth.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

record ChangePasswordRequest(@NotBlank @Size(max = 128) String currentPassword,
                             @NotBlank @Size(min = 8, max = 128) String newPassword) {

    // Os corpos de requisição podem aparecer em logs de depuração do Spring MVC.
    @Override
    public String toString() {
        return "ChangePasswordRequest[]";
    }
}
