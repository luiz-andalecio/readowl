package br.com.readowl.readowl.shared.security;

import br.com.readowl.readowl.shared.exception.ApiProblems;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.security.web.csrf.CsrfException;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;

/**
 * Responde às recusas da cadeia de segurança no mesmo formato dos erros da API,
 * em vez de redirecionar ou devolver HTML.
 */
@Component
class ProblemSecurityHandler implements AuthenticationEntryPoint, AccessDeniedHandler {

    private final JsonMapper jsonMapper;

    ProblemSecurityHandler(JsonMapper jsonMapper) {
        this.jsonMapper = jsonMapper;
    }

    @Override
    public void commence(HttpServletRequest request, HttpServletResponse response,
                         AuthenticationException authException) throws IOException {
        write(response, ApiProblems.of(HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED", "Autenticação necessária."));
    }

    @Override
    public void handle(HttpServletRequest request, HttpServletResponse response,
                       AccessDeniedException accessDeniedException) throws IOException {
        ProblemDetail problem = accessDeniedException instanceof CsrfException
                ? ApiProblems.of(HttpStatus.FORBIDDEN, "CSRF_TOKEN_INVALID", "Token CSRF ausente ou inválido.")
                : ApiProblems.of(HttpStatus.FORBIDDEN, "ACCESS_DENIED", "Você não tem permissão para esta operação.");
        write(response, problem);
    }

    private void write(HttpServletResponse response, ProblemDetail problem) throws IOException {
        response.setStatus(problem.getStatus());
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        jsonMapper.writeValue(response.getOutputStream(), problem);
    }
}
