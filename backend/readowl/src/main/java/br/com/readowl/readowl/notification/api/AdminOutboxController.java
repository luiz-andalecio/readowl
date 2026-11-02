package br.com.readowl.readowl.notification.api;

import br.com.readowl.readowl.notification.infrastructure.NotificationQueue;
import br.com.readowl.readowl.shared.api.PageRequests;
import br.com.readowl.readowl.shared.api.PageResponse;
import br.com.readowl.readowl.shared.exception.ApiException;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.Set;
import java.util.UUID;

/**
 * Eventos que esgotaram as tentativas. O alerta da fila traz alguém para cá, sem precisar alterar o banco.
 */
@RestController
@RequestMapping("/api/v1/admin/outbox-events")
class AdminOutboxController {

    private final NotificationQueue queue;

    AdminOutboxController(NotificationQueue queue) {
        this.queue = queue;
    }

    @GetMapping
    PageResponse<NotificationQueue.FailedEvent> failed(@RequestParam(defaultValue = "0") int page,
                                                       @RequestParam(defaultValue = "20") int size) {
        Pageable pageable = PageRequests.of(page, size, "failedAt", Set.of("failedAt"));
        long total = queue.countFailedEvents();
        return new PageResponse<>(queue.failedEvents(pageable.getPageSize(), pageable.getOffset()), pageable.getPageNumber(),
                pageable.getPageSize(), total, (int) Math.ceil((double) total / pageable.getPageSize()));
    }

    @PostMapping("/{id}/retry")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void retry(@PathVariable UUID id) {
        if (!queue.retryFailedEvent(id)) {
            throw ApiException.notFound("FAILED_EVENT_NOT_FOUND", "Não há evento com falha com este identificador.");
        }
    }
}
