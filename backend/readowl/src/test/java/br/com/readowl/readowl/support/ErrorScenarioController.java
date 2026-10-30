package br.com.readowl.readowl.support;

import br.com.readowl.readowl.shared.exception.ApiException;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Provoca cada categoria de falha para exercitar o tratamento de erros na pilha real.
 */
@RestController
@RequestMapping("/test/errors")
class ErrorScenarioController {

    static final String SENSITIVE_DETAIL = "duplicate key: email=leitora@example.com";

    record Payload(@NotBlank String name) {
    }

    @PostMapping("/validation")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void validation(@Valid @RequestBody Payload payload) {
    }

    @GetMapping("/api-exception")
    void apiException() {
        throw ApiException.conflict("BOOK_ALREADY_PUBLISHED", "A obra já está publicada.");
    }

    @GetMapping("/optimistic-lock")
    void optimisticLock() {
        throw new OptimisticLockingFailureException(SENSITIVE_DETAIL);
    }

    @GetMapping("/data-integrity")
    void dataIntegrity() {
        throw new DataIntegrityViolationException(SENSITIVE_DETAIL);
    }

    @GetMapping("/access-denied")
    void accessDenied() {
        throw new AccessDeniedException(SENSITIVE_DETAIL);
    }

    @GetMapping("/unexpected")
    void unexpected() {
        throw new IllegalStateException(SENSITIVE_DETAIL);
    }
}
