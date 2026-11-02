package br.com.readowl.readowl.media.application;

/**
 * Baixa uma imagem de um site permitido.
 */
public interface RemoteImages {

    /**
     * @return os bytes como vieram, ainda sem validação
     */
    byte[] download(String url);
}
