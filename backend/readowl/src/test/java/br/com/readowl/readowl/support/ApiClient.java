package br.com.readowl.readowl.support;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.HttpCookie;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Navegador simplificado: guarda cookies e, nas operações que alteram estado, envia o token CSRF como
 * o HttpClient do Angular faria.
 */
public final class ApiClient {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final String CSRF_COOKIE = "XSRF-TOKEN";

    private final String baseUrl;
    private final CookieManager cookies = new CookieManager(null, CookiePolicy.ACCEPT_ALL);
    private final HttpClient http;
    private final Map<String, String> headers = new LinkedHashMap<>();

    public ApiClient(int port) {
        this.baseUrl = "http://localhost:" + port;
        this.http = HttpClient.newBuilder().cookieHandler(cookies).build();
    }

    /**
     * Envia o cabeçalho em todos os pedidos deste cliente.
     */
    public ApiClient withHeader(String name, String value) {
        headers.put(name, value);
        return this;
    }

    public Response get(String path) {
        return send(request(path).GET());
    }

    /**
     * Sempre obtém o token antes de enviar: login e logout o descartam.
     */
    public Response post(String path, Object body) {
        return send(withCsrf(request(path)).POST(json(body)).header("Content-Type", "application/json"));
    }

    public Response postWithoutCsrf(String path, Object body) {
        return send(request(path).POST(json(body)).header("Content-Type", "application/json"));
    }

    public Response postWithCsrfHeader(String path, Object body, String csrfToken) {
        return send(request(path).POST(json(body)).header("Content-Type", "application/json")
                .header("X-XSRF-TOKEN", csrfToken));
    }

    public Response put(String path, Object body) {
        return send(withCsrf(request(path)).PUT(json(body)).header("Content-Type", "application/json"));
    }

    public Response patch(String path, Object body) {
        return send(withCsrf(request(path)).method("PATCH", json(body)).header("Content-Type", "application/json"));
    }

    public Response delete(String path) {
        return send(withCsrf(request(path)).DELETE());
    }

    public Response delete(String path, Object body) {
        return send(withCsrf(request(path)).method("DELETE", json(body)).header("Content-Type", "application/json"));
    }

    public Response head(String path) {
        return send(request(path).method("HEAD", HttpRequest.BodyPublishers.noBody()));
    }

    public Optional<String> cookie(String name) {
        return cookies.getCookieStore().getCookies().stream()
                .filter(cookie -> cookie.getName().equals(name))
                .map(HttpCookie::getValue)
                .findFirst();
    }

    /**
     * Copia a sessão para outro cliente, como um segundo uso do mesmo cookie.
     */
    public void useSessionCookie(String value) {
        HttpCookie cookie = new HttpCookie("SESSION", value);
        cookie.setPath("/");
        cookies.getCookieStore().add(URI.create(baseUrl), cookie);
    }

    private HttpRequest.Builder request(String path) {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(baseUrl + path));
        headers.forEach(request::header);
        return request;
    }

    /**
     * Pedidos paralelos de um mesmo cliente não podem buscar o primeiro token ao mesmo tempo: cada um
     * receberia um diferente, algo que o Angular não faz.
     */
    private synchronized HttpRequest.Builder withCsrf(HttpRequest.Builder request) {
        get("/api/v1/auth/csrf");
        return request.header("X-XSRF-TOKEN", cookie(CSRF_COOKIE).orElseThrow());
    }

    private static HttpRequest.BodyPublisher json(Object body) {
        return HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(body));
    }

    private Response send(HttpRequest.Builder request) {
        try {
            HttpResponse<String> response = http.send(request.build(), HttpResponse.BodyHandlers.ofString());
            return new Response(response.statusCode(), response.headers(), response.body());
        } catch (IOException ex) {
            throw new IllegalStateException(ex);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(ex);
        }
    }

    public record Response(int status, HttpHeaders headers, String body) {

        public JsonNode json() {
            return JSON.readTree(body);
        }

        public String code() {
            return json().path("code").asString();
        }

        public List<String> setCookies() {
            return headers.allValues("Set-Cookie");
        }

        public Optional<String> setCookie(String name) {
            return setCookies().stream().filter(cookie -> cookie.startsWith(name + "=")).findFirst();
        }

        public String location() {
            return headers.firstValue("Location").orElseThrow();
        }
    }

    public static Map<String, Object> body(Object... keysAndValues) {
        Map<String, Object> map = new LinkedHashMap<>();
        for (int i = 0; i < keysAndValues.length; i += 2) {
            map.put((String) keysAndValues[i], keysAndValues[i + 1]);
        }
        return map;
    }
}
