package br.com.readowl.readowl.support;

import br.com.readowl.readowl.media.application.ObjectStorage;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Guarda os arquivos em memória, no lugar do armazenamento real.
 */
public class RecordingObjectStorage implements ObjectStorage {

    private final Map<String, byte[]> objects = new ConcurrentHashMap<>();
    private volatile boolean failing;

    @Override
    public void put(String key, byte[] content, String contentType) {
        if (failing) {
            throw new IllegalStateException("Armazenamento simulado indisponível");
        }
        objects.put(key, content);
    }

    @Override
    public void delete(String key) {
        objects.remove(key);
    }

    public void setFailing(boolean failing) {
        this.failing = failing;
    }

    public byte[] get(String key) {
        return objects.get(key);
    }

    public boolean holds(String key) {
        return objects.containsKey(key);
    }
}
