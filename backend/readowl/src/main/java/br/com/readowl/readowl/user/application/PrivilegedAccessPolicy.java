package br.com.readowl.readowl.user.application;

import java.util.UUID;

/**
 * Confere se uma conta pode receber um papel de moderação ou administração. Fica aqui porque o módulo de usuários
 * não conhece as identidades externas.
 */
public interface PrivilegedAccessPolicy {

    void requireEligible(UUID userId);
}
