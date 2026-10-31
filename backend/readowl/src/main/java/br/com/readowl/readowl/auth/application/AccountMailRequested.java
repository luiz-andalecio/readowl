package br.com.readowl.readowl.auth.application;

/**
 * Pedido de envio de e-mail, atendido só depois do commit: uma transação desfeita não envia nada.
 *
 * @param token token em claro, ausente nos avisos que não têm link de uso único
 */
public record AccountMailRequested(Type type, String to, String token) {

    public enum Type {
        VERIFY_EMAIL,
        RESET_PASSWORD,
        ACCOUNT_ALREADY_EXISTS
    }

    @Override
    public String toString() {
        return "AccountMailRequested[type=" + type + "]";
    }
}
