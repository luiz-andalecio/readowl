package br.com.readowl.readowl.media.infrastructure;

import br.com.readowl.readowl.media.application.StoredImageType;
import br.com.readowl.readowl.shared.exception.ApiException;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpsConfigurator;
import com.sun.net.httpserver.HttpsServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Baixa de um servidor HTTPS de verdade, com certificado próprio, para exercitar o cliente HTTP real. Quem confere o destino
 * é substituído, porque o servidor está em localhost e em outra porta, o que {@link ImageDestinationsTest} confere que é recusado.
 */
class HttpRemoteImagesTest {

    private static final byte[] IMAGE = {1, 2, 3, 4};
    private static final char[] PASSWORD = "changeit".toCharArray();

    @TempDir
    private static Path certificates;

    private static HttpsServer server;
    private static SSLContext ssl;
    private static CountDownLatch release;
    private static final List<String> accepts = new CopyOnWriteArrayList<>();

    private final List<String> checked = new CopyOnWriteArrayList<>();
    private HttpRemoteImages remote;

    @BeforeAll
    static void startServer() throws Exception {
        ssl = contextFrom(generateKeyStore());
        server = HttpsServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.setHttpsConfigurator(new HttpsConfigurator(ssl));
        server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        server.createContext("/imagem", exchange -> {
            accepts.add(exchange.getRequestHeaders().getFirst("Accept"));
            respond(exchange, 200, IMAGE);
        });
        server.createContext("/ausente", exchange -> respond(exchange, 404, new byte[0]));
        server.createContext("/limite", exchange -> respond(exchange, 200, new byte[StoredImageType.MAX_FILE_BYTES]));
        server.createContext("/grande", exchange -> respond(exchange, 200, new byte[StoredImageType.MAX_FILE_BYTES + 1]));
        server.createContext("/parada", stalled());
        server.createContext("/sem-destino", exchange -> respond(exchange, 302, new byte[0]));
        server.createContext("/ciclo", redirectTo("/ciclo"));
        server.createContext("/proibido", exchange -> respond(exchange, 200, IMAGE));
        server.createContext("/para-proibido", redirectTo("/proibido"));
        server.createContext("/tres", redirectTo("/dois"));
        server.createContext("/dois", redirectTo("/um"));
        server.createContext("/um", redirectTo("/imagem"));
        server.createContext("/quatro", redirectTo("/tres"));
        server.start();
    }

    @AfterAll
    static void stopServer() {
        server.stop(0);
    }

    @BeforeEach
    void setUp() {
        accepts.clear();
        release = new CountDownLatch(1);
        HttpClient client = HttpClient.newBuilder().sslContext(ssl).followRedirects(HttpClient.Redirect.NEVER).build();
        remote = new HttpRemoteImages(uri -> {
            checked.add(uri.getPath());
            if (uri.getPath().equals("/proibido")) {
                throw new IOException("Destino recusado");
            }
        }, client, Duration.ofMillis(500));
    }

    private static String url(String path) {
        return "https://localhost:" + server.getAddress().getPort() + path;
    }

    @Test
    void downloadsTheImageAskingForTheFormatsWeAccept() {
        assertThat(remote.download(url("/imagem"))).isEqualTo(IMAGE);
        assertThat(accepts).containsExactly("image/jpeg,image/png,image/webp,image/gif");
    }

    @Test
    void everyRedirectIsCheckedAsANewAddress() {
        assertThat(remote.download(url("/tres"))).isEqualTo(IMAGE);
        assertThat(checked).containsExactly("/tres", "/dois", "/um", "/imagem");
    }

    @Test
    void aRedirectToARefusedAddressIsNotFollowed() {
        assertFailed("/para-proibido");
        assertThat(checked).containsExactly("/para-proibido", "/proibido");
    }

    @Test
    void tooManyRedirectsFail() {
        assertFailed("/quatro");
        assertFailed("/ciclo");
    }

    @Test
    void aRedirectWithoutADestinationFails() {
        assertFailed("/sem-destino");
    }

    @Test
    void anErrorResponseFails() {
        assertFailed("/ausente");
    }

    @Test
    void aSiteThatStopsSendingDoesNotHoldTheRequest() {
        long start = System.nanoTime();

        try {
            assertFailed("/parada");
        } finally {
            release.countDown();
        }

        assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofSeconds(4));
    }

    @Test
    void aBodyUpToTheLimitIsAccepted() {
        assertThat(remote.download(url("/limite"))).hasSize(StoredImageType.MAX_FILE_BYTES);
    }

    @Test
    void aBodyAboveTheLimitIsRefused() {
        assertThatThrownBy(() -> remote.download(url("/grande")))
                .isInstanceOf(ApiException.class)
                .extracting(ex -> ((ApiException) ex).getCode())
                .isEqualTo("IMAGE_FILE_TOO_LARGE");
    }

    @Test
    void aCertificateThatIsNotTrustedFails() {
        HttpRemoteImages strict = new HttpRemoteImages(uri -> {
        }, HttpClient.newHttpClient(), Duration.ofMillis(500));

        assertThatThrownBy(() -> strict.download(url("/imagem")))
                .isInstanceOf(ApiException.class)
                .extracting(ex -> ((ApiException) ex).getCode())
                .isEqualTo("IMAGE_DOWNLOAD_FAILED");
    }

    private void assertFailed(String path) {
        assertThatThrownBy(() -> remote.download(url(path)))
                .isInstanceOf(ApiException.class)
                .extracting(ex -> ((ApiException) ex).getCode())
                .isEqualTo("IMAGE_DOWNLOAD_FAILED");
    }

    private static void respond(HttpExchange exchange, int status, byte[] body) throws IOException {
        try (exchange) {
            exchange.sendResponseHeaders(status, body.length == 0 ? -1 : body.length);
            if (body.length > 0) {
                exchange.getResponseBody().write(body);
            }
        }
    }

    private static HttpHandler redirectTo(String path) {
        return exchange -> {
            exchange.getResponseHeaders().set("Location", path);
            respond(exchange, 302, new byte[0]);
        };
    }

    /**
     * Envia os cabeçalhos e o começo do corpo, e para.
     */
    private static HttpHandler stalled() {
        return exchange -> {
            try (exchange) {
                exchange.sendResponseHeaders(200, 0);
                exchange.getResponseBody().write(Arrays.copyOf(IMAGE, 2));
                exchange.getResponseBody().flush();
                release.await();
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
            }
        };
    }

    private static Path generateKeyStore() throws Exception {
        Path file = certificates.resolve("teste.p12");
        Process keytool = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "keytool").toString(),
                "-genkeypair", "-alias", "teste", "-keyalg", "RSA", "-keysize", "2048", "-validity", "1", "-dname", "CN=localhost",
                "-ext", "SAN=dns:localhost", "-storetype", "PKCS12", "-keystore", file.toString(), "-storepass", new String(PASSWORD))
                .redirectErrorStream(true).start();
        try (InputStream output = keytool.getInputStream()) {
            output.transferTo(java.io.OutputStream.nullOutputStream());
        }
        assertThat(keytool.waitFor()).isZero();
        return file;
    }

    private static SSLContext contextFrom(Path file) throws Exception {
        KeyStore store = KeyStore.getInstance("PKCS12");
        try (InputStream input = Files.newInputStream(file)) {
            store.load(input, PASSWORD);
        }
        KeyManagerFactory keys = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        keys.init(store, PASSWORD);
        TrustManagerFactory trust = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        trust.init(store);
        SSLContext context = SSLContext.getInstance("TLS");
        context.init(keys.getKeyManagers(), trust.getTrustManagers(), null);
        return context;
    }
}
