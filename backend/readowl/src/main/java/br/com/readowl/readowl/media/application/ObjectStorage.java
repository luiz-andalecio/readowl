package br.com.readowl.readowl.media.application;

/**
 * Armazenamento de arquivos públicos. Só existe com o armazenamento configurado: sem ele, a aplicação funciona, e só o envio
 * de fotos de perfil, capas e banners fica indisponível.
 */
public interface ObjectStorage {

    /**
     * @param key nome gerado pelo servidor e nunca reaproveitado, o que permite guardar em cache para sempre
     */
    void put(String key, byte[] content, String contentType);

    void delete(String key);
}
