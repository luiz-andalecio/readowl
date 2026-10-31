package br.com.readowl.readowl.user.domain;

public enum Role {
    USER,
    MODERATOR,
    ADMIN,
    ROOT;

    /**
     * Acesso total ao conteúdo de qualquer autor. Editar usuários é exclusivo de {@link #ROOT}.
     */
    public boolean isAdministrator() {
        return this == ADMIN || this == ROOT;
    }
}
