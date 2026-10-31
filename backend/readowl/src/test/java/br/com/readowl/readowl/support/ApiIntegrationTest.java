package br.com.readowl.readowl.support;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

import java.lang.annotation.ElementType;
import java.lang.annotation.Inherited;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Teste pela API real: servidor HTTP, cookies, sessões no PostgreSQL e cadeia de segurança completa.
 * O MockMvc com spring-security-test mascararia o comportamento de sessão e cookies.
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Inherited
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Import({TestDatabaseConfiguration.class, ApiTestConfiguration.class})
public @interface ApiIntegrationTest {
}
