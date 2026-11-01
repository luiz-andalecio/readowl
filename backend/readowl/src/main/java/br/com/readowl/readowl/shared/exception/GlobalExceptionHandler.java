package br.com.readowl.readowl.shared.exception;

import org.hibernate.exception.ConstraintViolationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import java.util.Set;

@RestControllerAdvice
public class GlobalExceptionHandler extends ResponseEntityExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);
    private static final Set<String> UNSTORABLE_TEXT_STATES = Set.of("22021", "22P05");

    @ExceptionHandler(ApiException.class)
    ProblemDetail handleApiException(ApiException ex) {
        ProblemDetail problem = ApiProblems.of(ex.getStatus(), ex.getCode(), ex.getMessage());
        ex.getProperties().forEach(problem::setProperty);
        return problem;
    }

    @ExceptionHandler(RateLimitExceededException.class)
    ResponseEntity<ProblemDetail> handleRateLimit(RateLimitExceededException ex) {
        return ResponseEntity.status(ex.getStatus())
                .header(HttpHeaders.RETRY_AFTER, String.valueOf(ex.getRetryAfterSeconds()))
                .body(ApiProblems.of(ex.getStatus(), ex.getCode(), ex.getMessage()));
    }

    @ExceptionHandler(OptimisticLockingFailureException.class)
    ProblemDetail handleOptimisticLock(OptimisticLockingFailureException ex) {
        return ApiProblems.of(HttpStatus.CONFLICT, "CONCURRENT_MODIFICATION",
                "O recurso foi alterado por outra operação. Recarregue e tente novamente.");
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    ProblemDetail handleDataIntegrity(DataIntegrityViolationException ex) {
        if (isUnstorableText(ex)) {
            return ApiProblems.of(HttpStatus.BAD_REQUEST, "INVALID_TEXT", "O texto tem caracteres não permitidos.");
        }
        // A mensagem do banco pode conter valores enviados pelo usuário: registra apenas a constraint.
        log.warn("Violacao de integridade: constraint={}", constraintName(ex));
        return ApiProblems.of(HttpStatus.CONFLICT, "DATA_CONFLICT",
                "A operação conflita com dados já existentes.");
    }

    @ExceptionHandler(AuthenticationException.class)
    ProblemDetail handleAuthentication(AuthenticationException ex) {
        return ApiProblems.of(HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED", "Autenticação necessária.");
    }

    @ExceptionHandler(AccessDeniedException.class)
    ProblemDetail handleAccessDenied(AccessDeniedException ex) {
        return ApiProblems.of(HttpStatus.FORBIDDEN, "ACCESS_DENIED", "Você não tem permissão para esta operação.");
    }

    @ExceptionHandler(Exception.class)
    ProblemDetail handleUnexpected(Exception ex) {
        log.error("Erro inesperado", ex);
        return ApiProblems.of(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR",
                "Ocorreu um erro inesperado. Informe o correlationId ao suporte.");
    }

    @Override
    protected ResponseEntity<Object> handleMethodArgumentNotValid(MethodArgumentNotValidException ex,
                                                                  HttpHeaders headers, HttpStatusCode status,
                                                                  WebRequest request) {
        List<Map<String, String>> errors = ex.getBindingResult().getFieldErrors().stream()
                .map(error -> Map.of(
                        "field", error.getField(),
                        "message", String.valueOf(error.getDefaultMessage())))
                .toList();
        ProblemDetail problem = ApiProblems.of(status, "VALIDATION_FAILED", "Dados inválidos.");
        problem.setProperty("errors", errors);
        return handleExceptionInternal(ex, problem, headers, status, request);
    }

    @Override
    protected ResponseEntity<Object> handleExceptionInternal(Exception ex, Object body, HttpHeaders headers,
                                                             HttpStatusCode status, WebRequest request) {
        ResponseEntity<Object> response = super.handleExceptionInternal(ex, body, headers, status, request);
        if (response != null && response.getBody() instanceof ProblemDetail problem && !hasCode(problem)) {
            ApiProblems.decorate(problem, defaultCode(status));
        }
        return response;
    }

    /**
     * O PostgreSQL não guarda o caractere nulo, em nenhum campo de texto ou JSON. O código do erro não varia
     * com o idioma do servidor, ao contrário da mensagem.
     */
    private static boolean isUnstorableText(Throwable failure) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof SQLException sql && UNSTORABLE_TEXT_STATES.contains(sql.getSQLState())) {
                return true;
            }
        }
        return false;
    }

    private static boolean hasCode(ProblemDetail problem) {
        return problem.getProperties() != null && problem.getProperties().containsKey("code");
    }

    private static String defaultCode(HttpStatusCode status) {
        HttpStatus resolved = HttpStatus.resolve(status.value());
        return resolved != null ? resolved.name() : "HTTP_" + status.value();
    }

    private static String constraintName(DataIntegrityViolationException ex) {
        return ex.getCause() instanceof ConstraintViolationException violation
                ? violation.getConstraintName()
                : "desconhecida";
    }
}
