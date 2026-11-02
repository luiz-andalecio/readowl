package br.com.readowl.readowl.auth.application;

/**
 * Identidade validada pelo Google. Nenhum token do provedor sai do fluxo de login.
 */
public record GoogleIdentity(String subject, String email, boolean emailVerified, String name, String picture) {
}
