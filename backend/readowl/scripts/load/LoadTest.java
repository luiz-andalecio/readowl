import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Teste de carga em Java puro, sem instalar nada. Simula leitores anônimos, leitores logados que consultam as
 * notificações, pessoas comentando e o lançamento de um capítulo de uma obra com muitos seguidores. Use por scripts/load/run.sh.
 * <p>
 * Cada usuário simulado usa um IP próprio em X-Forwarded-For, como faria atrás do proxy, para que os limites por IP não
 * dominem a medida. Sai com erro se alguma resposta for 5xx ou falhar a conexão.
 */
public class LoadTest {

    private static final String BASE = env("BASE_URL", "http://localhost:18080");
    private static final String MANAGEMENT = env("MANAGEMENT_URL", "http://localhost:18081");
    private static final int READERS = number("READERS", 500);
    private static final int LOGGED = number("LOGGED", 200);
    private static final int COMMENTERS = number("COMMENTERS", 20);
    private static final int SECONDS = number("DURATION_SECONDS", 60);
    private static final int THINK_MIN = number("THINK_MIN_MS", 5000);
    private static final int THINK_MAX = number("THINK_MAX_MS", 15000);
    private static final int POLL_MS = number("POLL_MS", 30000);
    private static final int RELEASE_AT = number("RELEASE_AT_SECONDS", 0);
    private static final int WARMUP = number("WARMUP_SECONDS", 60);
    private static final String PASSWORD = env("LOAD_PASSWORD", "senha-de-carga-123");
    private static final String HOT_BOOK = "c1-obra-de-carga-1";
    private static final Pattern ID = Pattern.compile("\"id\":\"([0-9a-f-]{36})\"");
    private static final Pattern VERSION = Pattern.compile("\"version\":(\\d+)");
    private static final Pattern SLUG = Pattern.compile("\"slug\":\"(c\\d+-[a-z0-9-]+)\"");

    private static final HttpClient HTTP = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(Duration.ofSeconds(10)).build();
    private static final Map<String, ConcurrentLinkedQueue<Long>> TIMES = new ConcurrentHashMap<>();
    private static final Map<String, AtomicLong> SERVER_ERRORS = new ConcurrentHashMap<>();
    private static final Map<String, AtomicLong> REFUSALS = new ConcurrentHashMap<>();
    private static final AtomicLong CONNECTION_FAILURES = new AtomicLong();
    private static final AtomicInteger NEXT_ADDRESS = new AtomicInteger();
    private static final Semaphore LOGINS = new Semaphore(4);

    private static volatile boolean measuring;
    private static volatile boolean running = true;
    private static volatile String releasedChapter;

    private static String env(String name, String fallback) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? fallback : value;
    }

    private static int number(String name, int fallback) {
        return Integer.parseInt(env(name, Integer.toString(fallback)));
    }

    /**
     * Um usuário simulado: IP próprio e cookies, sem nada guardado entre usuários.
     */
    private static final class Client {
        private final String address;
        private String session;
        private String csrf;

        Client() {
            int n = NEXT_ADDRESS.incrementAndGet();
            address = "10." + (n >> 16 & 255) + "." + (n >> 8 & 255) + "." + (n & 255);
        }

        HttpResponse<String> send(String route, String method, String path, String body) throws InterruptedException {
            HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(BASE + path))
                    .timeout(Duration.ofSeconds(60))
                    .header("X-Forwarded-For", address)
                    .header("X-Forwarded-Proto", "https");
            List<String> cookies = new ArrayList<>();
            if (session != null) {
                cookies.add("__Host-SESSION=" + session);
            }
            if (csrf != null) {
                cookies.add("__Host-XSRF-TOKEN=" + csrf);
                request.header("X-XSRF-TOKEN", csrf);
            }
            if (!cookies.isEmpty()) {
                request.header("Cookie", String.join("; ", cookies));
            }
            if (body != null) {
                request.header("Content-Type", "application/json");
                request.method(method, HttpRequest.BodyPublishers.ofString(body));
            } else {
                request.method(method, HttpRequest.BodyPublishers.noBody());
            }
            long start = System.nanoTime();
            try {
                HttpResponse<String> response = HTTP.send(request.build(), HttpResponse.BodyHandlers.ofString());
                long elapsed = (System.nanoTime() - start) / 1_000_000;
                if (measuring) {
                    TIMES.computeIfAbsent(route, key -> new ConcurrentLinkedQueue<>()).add(elapsed);
                    if (response.statusCode() >= 500) {
                        SERVER_ERRORS.computeIfAbsent(route, key -> new AtomicLong()).incrementAndGet();
                    } else if (response.statusCode() >= 400) {
                        REFUSALS.computeIfAbsent(route + " " + response.statusCode(), key -> new AtomicLong()).incrementAndGet();
                    }
                }
                response.headers().allValues("set-cookie").forEach(this::remember);
                return response;
            } catch (java.io.IOException failure) {
                if (measuring) {
                    CONNECTION_FAILURES.incrementAndGet();
                }
                return null;
            }
        }

        private void remember(String cookie) {
            String value = cookie.substring(cookie.indexOf('=') + 1).split(";", 2)[0];
            if (cookie.startsWith("__Host-SESSION=")) {
                session = value;
            } else if (cookie.startsWith("__Host-XSRF-TOKEN=")) {
                csrf = value.isEmpty() ? null : value;
            }
        }

        boolean login(String email) throws InterruptedException {
            LOGINS.acquire();
            try {
                send("GET /auth/csrf", "GET", "/api/v1/auth/csrf", null);
                HttpResponse<String> login = send("POST /auth/login", "POST", "/api/v1/auth/login",
                        "{\"email\":\"" + email + "\",\"password\":\"" + PASSWORD + "\"}");
                if (login == null || login.statusCode() != 200) {
                    System.err.println("Login recusado: " + email + " " + (login == null ? "sem resposta" : login.statusCode() + " " + login.body()));
                    return false;
                }
                send("GET /auth/csrf", "GET", "/api/v1/auth/csrf", null);
                return true;
            } finally {
                LOGINS.release();
            }
        }
    }

    private static void think(int min, int max) throws InterruptedException {
        Thread.sleep(ThreadLocalRandom.current().nextInt(min, max + 1));
    }

    /**
     * 80% das visitas vão para 5% das obras, como costuma acontecer em um catálogo.
     */
    private static String pickBook(List<String> books) {
        ThreadLocalRandom random = ThreadLocalRandom.current();
        int hot = Math.max(1, books.size() / 20);
        return random.nextInt(100) < 80 ? books.get(random.nextInt(hot)) : books.get(random.nextInt(books.size()));
    }

    private static void read(Client client, List<String> books) throws InterruptedException {
        ThreadLocalRandom random = ThreadLocalRandom.current();
        String released = releasedChapter;
        if (released != null && random.nextBoolean()) {
            client.send("GET capítulo lançado", "GET", "/api/v1/books/" + HOT_BOOK + "/chapters/" + released, null);
            return;
        }
        int step = random.nextInt(100);
        String book = pickBook(books);
        if (step < 15) {
            client.send("GET /books", "GET", "/api/v1/books?page=" + random.nextInt(20) + "&size=20", null);
        } else if (step < 20) {
            client.send("GET /books?query", "GET", "/api/v1/books?query=carga+" + random.nextInt(5000), null);
        } else if (step < 40) {
            client.send("GET /books/{slug}", "GET", "/api/v1/books/" + book, null);
        } else if (step < 50) {
            client.send("GET /books/{slug}/chapters", "GET", "/api/v1/books/" + book + "/chapters?size=50", null);
        } else {
            client.send("GET capítulo", "GET", "/api/v1/books/" + book + "/chapters/capitulo-" + random.nextInt(1, 11), null);
        }
    }

    private static void reader(List<String> books) {
        Client client = new Client();
        try {
            think(0, THINK_MAX);
            while (running) {
                read(client, books);
                think(THINK_MIN, THINK_MAX);
            }
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * Logado: consulta as notificações em intervalo fixo e lê, com sessão, as mesmas páginas.
     */
    private static void loggedReader(int account, List<String> books) {
        Client client = new Client();
        try {
            think(0, THINK_MAX);
            if (!client.login("carga" + account + "@example.com")) {
                return;
            }
            long nextPoll = 0;
            while (running) {
                if (System.currentTimeMillis() >= nextPoll) {
                    client.send("GET /notifications/unread-count", "GET", "/api/v1/notifications/unread-count", null);
                    nextPoll = System.currentTimeMillis() + POLL_MS;
                }
                read(client, books);
                think(THINK_MIN, THINK_MAX);
            }
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
    }

    private static void commenter(int account, String bookId) {
        Client client = new Client();
        int sent = 0;
        try {
            think(0, THINK_MAX);
            if (!client.login("carga" + account + "@example.com")) {
                return;
            }
            while (running) {
                client.send("POST comentário", "POST", "/api/v1/books/" + bookId + "/comments",
                        "{\"content\":\"Comentário " + account + "-" + (sent++) + "\"}");
                client.send("GET comentários da obra popular", "GET", "/api/v1/books/" + bookId + "/comments?size=20", null);
                think(10000, 30000);
            }
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * O autor da obra popular publica um capítulo novo e metade dos leitores corre para ele.
     */
    private static void release(String bookId) {
        try {
            Thread.sleep(RELEASE_AT * 1000L);
            String author = env("HOT_AUTHOR_EMAIL", "carga2@example.com");
            Client client = new Client();
            if (!client.login(author)) {
                return;
            }
            String slug = "lancamento-" + System.currentTimeMillis();
            HttpResponse<String> created = client.send("POST capítulo", "POST", "/api/v1/books/" + bookId + "/chapters",
                    "{\"title\":\"" + slug + "\"}");
            if (created == null || created.statusCode() != 201) {
                System.err.println("Capítulo recusado: " + (created == null ? "sem resposta" : created.body()));
                return;
            }
            String chapter = first(ID, created.body()).orElseThrow();
            String actualSlug = first(Pattern.compile("\"slug\":\"([^\"]+)\""), created.body()).orElseThrow();
            HttpResponse<String> draft = client.send("GET rascunho", "GET", "/api/v1/chapters/" + chapter + "/draft", null);
            String version = first(VERSION, draft.body()).orElseThrow();
            String document = "{\"type\":\"doc\",\"content\":[{\"type\":\"paragraph\",\"content\":[{\"type\":\"text\",\"text\":\""
                    + "Era uma vez. ".repeat(500) + "\"}]}]}";
            HttpResponse<String> saved = client.send("PUT rascunho", "PUT", "/api/v1/chapters/" + chapter + "/draft",
                    "{\"version\":" + version + ",\"document\":" + document + "}");
            String saved_version = first(VERSION, saved.body()).orElseThrow();
            HttpResponse<String> published = client.send("POST publicação", "POST", "/api/v1/chapters/" + chapter + "/publish",
                    "{\"draftVersion\":" + saved_version + "}");
            if (published.statusCode() == 200) {
                releasedChapter = actualSlug;
                System.out.println("Capítulo lançado: " + actualSlug + " em " + chapter);
            } else {
                System.err.println("Publicação recusada: " + published.body());
            }
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
    }

    private static Optional<String> first(Pattern pattern, String text) {
        Matcher matcher = pattern.matcher(text);
        return matcher.find() ? Optional.of(matcher.group(1)) : Optional.empty();
    }

    private static double metric(String name) {
        try {
            HttpResponse<String> response = HTTP.send(HttpRequest.newBuilder(URI.create(MANAGEMENT + "/actuator/metrics/" + name))
                    .timeout(Duration.ofSeconds(5)).build(), HttpResponse.BodyHandlers.ofString());
            Matcher matcher = Pattern.compile("\"value\":([0-9.E+-]+)").matcher(response.body());
            return matcher.find() ? Double.parseDouble(matcher.group(1)) : -1;
        } catch (Exception ex) {
            return -1;
        }
    }

    public static void main(String[] args) throws Exception {
        Client probe = new Client();
        List<String> books = new ArrayList<>();
        for (int page = 0; page < 10; page++) {
            HttpResponse<String> catalog = probe.send("preparo", "GET", "/api/v1/books?page=" + page + "&size=50", null);
            if (catalog == null || catalog.statusCode() != 200) {
                throw new IllegalStateException("O catálogo não respondeu: " + (catalog == null ? "sem resposta" : catalog.statusCode()));
            }
            Matcher matcher = SLUG.matcher(catalog.body());
            while (matcher.find()) {
                books.add(matcher.group(1));
            }
        }
        String hotBookId = first(ID, probe.send("preparo", "GET", "/api/v1/books/" + HOT_BOOK, null).body()).orElseThrow();
        System.out.printf("Leitores %d, logados %d, comentando %d, %d s, pausa de %d a %d ms, %d obras%n",
                READERS, LOGGED, COMMENTERS, SECONDS, THINK_MIN, THINK_MAX, books.size());

        try (ExecutorService users = Executors.newVirtualThreadPerTaskExecutor()) {
            for (int n = 0; n < READERS; n++) {
                users.submit(() -> reader(books));
            }
            for (int n = 0; n < LOGGED; n++) {
                int account = 100 + n;
                users.submit(() -> loggedReader(account, books));
            }
            for (int n = 0; n < COMMENTERS; n++) {
                int account = 50 + n;
                users.submit(() -> commenter(account, hotBookId));
            }
            if (RELEASE_AT > 0) {
                users.submit(() -> release(hotBookId));
            }

            // Os usuários entram aos poucos e a JVM começa fria: mede depois de os logins terminarem e do aquecimento.
            Thread.sleep(WARMUP * 1000L);
            measuring = true;
            double maxActive = 0;
            double maxPending = 0;
            double maxHeap = 0;
            long end = System.currentTimeMillis() + SECONDS * 1000L;
            while (System.currentTimeMillis() < end) {
                maxActive = Math.max(maxActive, metric("hikaricp.connections.active"));
                maxPending = Math.max(maxPending, metric("hikaricp.connections.pending"));
                maxHeap = Math.max(maxHeap, metric("jvm.memory.used?tag=area:heap"));
                Thread.sleep(1000);
            }
            measuring = false;
            running = false;
            users.shutdownNow();
            report(maxActive, maxPending, maxHeap);
        }
    }

    private static void report(double maxActive, double maxPending, double maxHeap) {
        long total = 0;
        long errors = CONNECTION_FAILURES.get();
        System.out.printf("%-34s %8s %8s %8s %8s %8s %6s%n", "Rota", "Pedidos", "p50 ms", "p95 ms", "p99 ms", "Máx ms", "5xx");
        for (Map.Entry<String, ConcurrentLinkedQueue<Long>> route : new TreeMap<>(TIMES).entrySet()) {
            long[] times = route.getValue().stream().mapToLong(Long::longValue).sorted().toArray();
            total += times.length;
            long failed = Optional.ofNullable(SERVER_ERRORS.get(route.getKey())).map(AtomicLong::get).orElse(0L);
            errors += failed;
            System.out.printf("%-34s %8d %8d %8d %8d %8d %6d%n", route.getKey(), times.length,
                    percentile(times, 50), percentile(times, 95), percentile(times, 99), times[times.length - 1], failed);
        }
        System.out.printf("Total: %d pedidos, %.1f por segundo. Falhas de conexão: %d. Respostas 5xx ou falhas: %d%n",
                total, total / (double) SECONDS, CONNECTION_FAILURES.get(), errors);
        System.out.printf("Pool: até %.0f conexões em uso, até %.0f na fila. Heap: até %.0f MiB%n",
                maxActive, maxPending, maxHeap / (1024 * 1024));
        if (!REFUSALS.isEmpty()) {
            System.out.println("Recusas 4xx: " + new TreeMap<>(REFUSALS));
        }
        if (errors > 0) {
            System.exit(1);
        }
    }

    private static long percentile(long[] sorted, int percent) {
        return sorted[Math.min(sorted.length - 1, (int) Math.ceil(percent / 100.0 * sorted.length) - 1)];
    }
}
