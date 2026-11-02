package br.com.readowl.readowl.media.application;

/**
 * Diz se um arquivo que saiu de uso ainda precisa ficar guardado. Quem decide é a moderação, que este módulo não conhece.
 */
public interface ImageHolds {

    boolean isHeld(String key);
}
