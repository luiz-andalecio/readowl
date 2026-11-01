package br.com.readowl.readowl.shared.security;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.core.Authentication;

/**
 * Confere uma sessão contra os dados atuais da conta. Fica aqui porque shared não conhece o módulo de usuários.
 */
public interface SessionAccessCheck {

    boolean isCurrent(Authentication authentication);

    void reject(HttpServletRequest request, HttpServletResponse response);
}
