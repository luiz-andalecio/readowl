package br.com.readowl.readowl.user.application;

import java.util.UUID;

/**
 * O papel ou o status mudou. As sessões guardam o papel, então as existentes precisam ser encerradas.
 */
public record UserAccessChanged(UUID userId) {
}
