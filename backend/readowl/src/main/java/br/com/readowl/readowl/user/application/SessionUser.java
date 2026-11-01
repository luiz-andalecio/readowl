package br.com.readowl.readowl.user.application;

import br.com.readowl.readowl.shared.exception.ApiException;
import br.com.readowl.readowl.user.domain.Role;
import org.springframework.security.core.AuthenticatedPrincipal;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

import java.io.Serial;
import java.io.Serializable;
import java.util.List;
import java.util.UUID;

/**
 * Usuário autenticado, guardado na sessão. O nome é o ID, e não o e-mail, para que a revogação de
 * sessões por usuário continue válida se o e-mail mudar.
 * <p>
 * É serializado no banco: renomear ou mover esta classe invalida as sessões existentes.
 */
public record SessionUser(UUID id, Role role) implements AuthenticatedPrincipal, Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    @Override
    public String getName() {
        return id.toString();
    }

    public boolean isAdministrator() {
        return role.isAdministrator();
    }

    /**
     * Quem modera não decide sobre o próprio conteúdo: o caso fica para outra pessoa. A administração, que tem
     * acesso total e pode não ter a quem recorrer, fica de fora, e a ação continua na auditoria.
     */
    public void requireIndependentReview(UUID contentOwnerId) {
        if (!isAdministrator() && id.equals(contentOwnerId)) {
            throw ApiException.forbidden("OWN_CONTENT_MODERATION", "Outra pessoa da moderação precisa analisar o seu conteúdo.");
        }
    }

    public List<GrantedAuthority> authorities() {
        return List.of(new SimpleGrantedAuthority("ROLE_" + role.name()));
    }
}
