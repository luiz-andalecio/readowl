package br.com.readowl.readowl.auth.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

record TokenRequest(@NotBlank @Size(max = 128) String token) {

    // Os corpos de requisição podem aparecer em logs de depuração do Spring MVC.
    @Override
    public String toString() {
        return "TokenRequest[]";
    }
}
