package br.com.readowl.readowl.shared.api;

import br.com.readowl.readowl.support.IntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * O contrato em Markdown é a referência do frontend, e nada impediria que ele ficasse para trás. Só confere se
 * as rotas existem nos dois lados, não os campos.
 */
@IntegrationTest
class ContractCoverageIntegrationTest {

    private static final Path CONTRACT = Path.of("..", "..", "docs", "backend-api-contract.md");
    private static final String API_PREFIX = "/api/v1";
    private static final Pattern DOCUMENTED_ROUTE = Pattern.compile("`(GET|POST|PUT|PATCH|DELETE) (/[^`?\\s]*)");
    // Atendidas por filtros, e não por controllers: Actuator, as rotas do Spring Security para login e logout, e o NGINX.
    private static final List<String> OUTSIDE_THE_CONTROLLERS = List.of("/actuator", "/oauth2", "/login/oauth2", "/auth/logout", "/healthz");

    @Autowired
    @Qualifier("requestMappingHandlerMapping")
    private RequestMappingHandlerMapping mappings;

    private Set<String> implementedRoutes() {
        Set<String> routes = new TreeSet<>();
        for (RequestMappingInfo info : mappings.getHandlerMethods().keySet()) {
            for (String path : info.getPathPatternsCondition().getPatternValues()) {
                if (path.startsWith(API_PREFIX)) {
                    for (RequestMethod method : info.getMethodsCondition().getMethods()) {
                        routes.add(method + " " + normalize(path.substring(API_PREFIX.length())));
                    }
                }
            }
        }
        return routes;
    }

    private static Set<String> documentedRoutes() throws IOException {
        Set<String> routes = new TreeSet<>();
        Matcher matcher = DOCUMENTED_ROUTE.matcher(Files.readString(CONTRACT, StandardCharsets.UTF_8));
        while (matcher.find()) {
            // Os exemplos da auditoria citam o caminho completo.
            String path = matcher.group(2).replaceFirst("^" + API_PREFIX, "");
            if (OUTSIDE_THE_CONTROLLERS.stream().noneMatch(path::startsWith)) {
                routes.add(matcher.group(1) + " " + normalize(path));
            }
        }
        return routes;
    }

    /**
     * O contrato e o código podem dar nomes diferentes às variáveis do caminho.
     */
    private static String normalize(String path) {
        return path.replaceAll("\\{[^}]*}", "{}");
    }

    @Test
    void everyRouteIsInTheContract() throws IOException {
        Set<String> missing = new TreeSet<>(implementedRoutes());
        missing.removeAll(documentedRoutes());

        assertThat(missing).as("rotas sem documentação em docs/backend-api-contract.md").isEmpty();
    }

    @Test
    void theContractDoesNotDescribeRoutesThatDoNotExist() throws IOException {
        Set<String> stale = new TreeSet<>(documentedRoutes());
        stale.removeAll(implementedRoutes());

        assertThat(stale).as("rotas documentadas que não existem").isEmpty();
    }
}
