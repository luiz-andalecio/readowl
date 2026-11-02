package br.com.readowl.readowl.support;

import br.com.readowl.readowl.media.application.RemoteImages;
import br.com.readowl.readowl.shared.exception.ApiException;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Responde com as imagens cadastradas, no lugar do download real.
 */
public class FakeRemoteImages implements RemoteImages {

    private final Map<String, byte[]> images = new ConcurrentHashMap<>();
    private final List<String> downloads = new CopyOnWriteArrayList<>();

    public void serve(String url, byte[] image) {
        images.put(url, image);
    }

    public List<String> downloads() {
        return downloads;
    }

    public void reset() {
        images.clear();
        downloads.clear();
    }

    @Override
    public byte[] download(String url) {
        downloads.add(url);
        byte[] image = images.get(url);
        if (image == null) {
            throw ApiException.badRequest("IMAGE_DOWNLOAD_FAILED", "Não foi possível baixar a imagem.");
        }
        return image;
    }
}
