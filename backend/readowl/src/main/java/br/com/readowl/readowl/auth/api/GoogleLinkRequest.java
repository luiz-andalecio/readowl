package br.com.readowl.readowl.auth.api;

import jakarta.validation.constraints.Size;

/**
 * @param password senha atual, exigida quando a conta tem senha
 */
record GoogleLinkRequest(@Size(max = 128) String password) {

    // Os corpos de requisição podem aparecer em logs de depuração do Spring MVC.
    @Override
    public String toString() {
        return "GoogleLinkRequest[]";
    }
}
