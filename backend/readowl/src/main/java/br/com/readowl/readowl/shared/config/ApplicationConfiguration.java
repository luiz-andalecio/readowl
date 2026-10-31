package br.com.readowl.readowl.shared.config;

import org.slf4j.MDC;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.jackson.autoconfigure.JsonFactoryBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.TaskDecorator;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;
import tools.jackson.core.StreamReadConstraints;

import java.util.Map;

@Configuration(proxyBeanMethods = false)
@EnableAsync
@EnableScheduling
@EnableConfigurationProperties(ReadowlProperties.class)
class ApplicationConfiguration {

    private static final long MAX_JSON_BYTES = 3L * 1024 * 1024;
    private static final int MAX_JSON_DEPTH = 100;

    /**
     * Interrompe a leitura de corpos JSON grandes, mesmo enviados sem Content-Length. O limite fica acima
     * do tamanho máximo de um capítulo.
     */
    @Bean
    JsonFactoryBuilderCustomizer jsonSizeLimits() {
        return factory -> factory.streamReadConstraints(StreamReadConstraints.builder()
                .maxDocumentLength(MAX_JSON_BYTES)
                .maxNestingDepth(MAX_JSON_DEPTH)
                .build());
    }

    /**
     * Mantém o ID de correlação nos logs de tarefas assíncronas.
     */
    @Bean
    TaskDecorator correlationIdTaskDecorator() {
        return task -> {
            Map<String, String> context = MDC.getCopyOfContextMap();
            return () -> {
                if (context != null) {
                    MDC.setContextMap(context);
                }
                try {
                    task.run();
                } finally {
                    MDC.clear();
                }
            };
        };
    }
}
