package br.com.readowl.readowl.auth.application;

import java.util.UUID;

/**
 * Pedido de envio já gravado na fila, atendido só depois do commit: uma transação desfeita não envia nada.
 */
record AccountMailRequested(UUID id) {
}
