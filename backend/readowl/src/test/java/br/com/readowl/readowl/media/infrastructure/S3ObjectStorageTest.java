package br.com.readowl.readowl.media.infrastructure;

import br.com.readowl.readowl.shared.config.ReadowlProperties;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * O cliente real contra um servidor local: confere o que um serviço compatível com S3 recebe.
 */
class S3ObjectStorageTest {

    private record Received(String method, String path, Map<String, List<String>> headers, byte[] body) {
    }

    private final List<Received> received = new CopyOnWriteArrayList<>();
    private volatile int status = 200;
    private HttpServer server;
    private S3ObjectStorage storage;

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            received.add(new Received(exchange.getRequestMethod(), exchange.getRequestURI().getPath(),
                    Map.copyOf(exchange.getRequestHeaders()), exchange.getRequestBody().readAllBytes()));
            exchange.getResponseHeaders().add("ETag", "\"abc\"");
            exchange.sendResponseHeaders(status, -1);
            exchange.close();
        });
        server.start();
        storage = new S3ObjectStorage(new ReadowlProperties.Storage("http://127.0.0.1:" + server.getAddress().getPort(),
                "auto", "fotos", "chave-de-teste", "segredo-de-teste", "https://cdn.readowl.test"));
    }

    @AfterEach
    void stop() {
        storage.close();
        server.stop(0);
    }

    private static String header(Received request, String name) {
        return request.headers().entrySet().stream().filter(entry -> entry.getKey().equalsIgnoreCase(name))
                .map(entry -> entry.getValue().getFirst()).findFirst().orElse(null);
    }

    @Test
    void sendsTheFileToTheBucketPathSignedAndCacheable() {
        byte[] content = {1, 2, 3, 4, 5};

        storage.put("avatars/conta/foto.jpg", content, "image/jpeg");

        Received put = received.getFirst();
        assertThat(put.method()).isEqualTo("PUT");
        assertThat(put.path()).isEqualTo("/fotos/avatars/conta/foto.jpg");
        assertThat(put.body()).isEqualTo(content);
        assertThat(header(put, "Content-Type")).isEqualTo("image/jpeg");
        assertThat(header(put, "Cache-Control")).contains("immutable");
        assertThat(header(put, "Authorization")).startsWith("AWS4-HMAC-SHA256").contains("chave-de-teste").doesNotContain("segredo-de-teste");
    }

    @Test
    void doesNotUseTheChunkedChecksumFormatThatCompatibleServicesRefuse() {
        storage.put("a.jpg", new byte[]{1}, "image/jpeg");

        Received put = received.getFirst();
        assertThat(header(put, "Content-Encoding")).isNull();
        assertThat(header(put, "x-amz-trailer")).isNull();
        assertThat(header(put, "x-amz-sdk-checksum-algorithm")).isNull();
    }

    @Test
    void deletesFromTheBucketPath() {
        status = 204;

        storage.delete("avatars/conta/foto.jpg");

        assertThat(received.getFirst().method()).isEqualTo("DELETE");
        assertThat(received.getFirst().path()).isEqualTo("/fotos/avatars/conta/foto.jpg");
    }

    @Test
    void aRefusalFromTheServiceBecomesAnError() {
        status = 403;

        assertThatThrownBy(() -> storage.put("a.jpg", new byte[]{1}, "image/jpeg")).isInstanceOf(RuntimeException.class);
    }
}
