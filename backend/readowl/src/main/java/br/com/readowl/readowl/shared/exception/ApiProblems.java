package br.com.readowl.readowl.shared.exception;

import br.com.readowl.readowl.shared.logging.CorrelationIdFilter;
import org.slf4j.MDC;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;

/**
 * Forma única das respostas de erro (RFC 9457), com um {@code code} estável para o cliente.
 */
public final class ApiProblems {

    private static final String CODE = "code";
    private static final String CORRELATION_ID = "correlationId";

    private ApiProblems() {
    }

    public static ProblemDetail of(HttpStatusCode status, String code, String detail) {
        return decorate(ProblemDetail.forStatusAndDetail(status, detail), code);
    }

    public static ProblemDetail decorate(ProblemDetail problem, String code) {
        problem.setProperty(CODE, code);
        String correlationId = MDC.get(CorrelationIdFilter.MDC_KEY);
        if (correlationId != null) {
            problem.setProperty(CORRELATION_ID, correlationId);
        }
        return problem;
    }
}
