package br.com.readowl.readowl.media.infrastructure;

import br.com.readowl.readowl.media.application.ExternalImages;
import br.com.readowl.readowl.media.application.RemoteImages;
import br.com.readowl.readowl.media.application.StoredImageType;
import br.com.readowl.readowl.shared.exception.ApiException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Cada redirecionamento é conferido como um endereço novo, por isso o cliente não os segue sozinho.
 */
@Component
class HttpRemoteImages implements RemoteImages {

    private static final Logger log = LoggerFactory.getLogger(HttpRemoteImages.class);

    private static final int MAX_REDIRECTS = 3;
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(3);
    private static final Duration RESPONSE_TIMEOUT = Duration.ofSeconds(5);
    private static final Duration BODY_TIMEOUT = Duration.ofSeconds(10);
    private static final List<Integer> REDIRECTS = List.of(301, 302, 303, 307, 308);

    @FunctionalInterface
    interface Destinations {
        void requireSafe(URI uri) throws IOException;
    }

    private final Destinations destinations;
    private final HttpClient client;
    private final Duration bodyTimeout;

    @Autowired
    HttpRemoteImages(ExternalImages images) {
        this(new ImageDestinations(images::isAllowed, InetAddress::getAllByName)::requireSafe,
                HttpClient.newBuilder().connectTimeout(CONNECT_TIMEOUT).followRedirects(HttpClient.Redirect.NEVER).build(),
                BODY_TIMEOUT);
    }

    HttpRemoteImages(Destinations destinations, HttpClient client, Duration bodyTimeout) {
        this.destinations = destinations;
        this.client = client;
        this.bodyTimeout = bodyTimeout;
    }

    @Override
    public byte[] download(String url) {
        URI current = URI.create(url);
        try {
            for (int redirects = 0; redirects <= MAX_REDIRECTS; redirects++) {
                destinations.requireSafe(current);
                HttpResponse<InputStream> response = client.send(request(current), HttpResponse.BodyHandlers.ofInputStream());
                try (InputStream body = response.body()) {
                    if (!REDIRECTS.contains(response.statusCode())) {
                        if (response.statusCode() != 200) {
                            throw new IOException("Resposta " + response.statusCode());
                        }
                        return read(body);
                    }
                    current = current.resolve(response.headers().firstValue("Location").orElseThrow(() -> new IOException("Sem destino")));
                }
            }
            throw new IOException("Redirecionamentos demais");
        } catch (IOException | IllegalArgumentException ex) {
            throw failed(url, ex);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw failed(url, ex);
        }
    }

    private static HttpRequest request(URI uri) {
        return HttpRequest.newBuilder(uri).timeout(RESPONSE_TIMEOUT)
                .header("Accept", "image/jpeg,image/png,image/webp,image/gif")
                .header("User-Agent", "Readowl")
                .GET().build();
    }

    /**
     * Um site lento não prende a requisição: ao fim do prazo, fechar o corpo interrompe a leitura.
     */
    private byte[] read(InputStream body) throws IOException, InterruptedException {
        FutureTask<byte[]> reading = new FutureTask<>(() -> body.readNBytes(StoredImageType.MAX_FILE_BYTES + 1));
        Thread.ofVirtual().start(reading);
        byte[] bytes;
        try {
            bytes = reading.get(bodyTimeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException | ExecutionException ex) {
            throw new IOException("Leitura interrompida", ex);
        } finally {
            reading.cancel(true);
        }
        if (bytes.length > StoredImageType.MAX_FILE_BYTES) {
            throw ApiException.badRequest("IMAGE_FILE_TOO_LARGE", "A imagem pode ter no máximo 5 MiB.");
        }
        return bytes;
    }

    /**
     * O registro fica só com o domínio informado: o caminho do endereço pode ter dados de terceiros.
     */
    private static ApiException failed(String url, Exception cause) {
        log.warn("Imagem nao baixada: dominio={}, causa={}", URI.create(url).getHost(), cause.getClass().getSimpleName());
        return ApiException.badRequest("IMAGE_DOWNLOAD_FAILED", "Não foi possível baixar a imagem. Confira o endereço ou envie o arquivo.");
    }
}
