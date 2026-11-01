package br.com.readowl.readowl.shared.security;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import org.springframework.security.core.Authentication;

/**
 * Confere uma sessão contra os dados atuais da conta. Fica aqui porque shared não conhece o módulo de usuários.
 */
public interface SessionAccessCheck {

    boolean isCurrent(Authentication authentication);

    /**
     * A sessão ainda está dentro da duração máxima, que vale mesmo com uso contínuo.
     */
    boolean isWithinMaxAge(Authentication authentication, HttpSession session);

    void reject(HttpServletRequest request, HttpServletResponse response);
}
