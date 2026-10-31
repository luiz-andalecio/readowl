package br.com.readowl.readowl.notification.api;

import br.com.readowl.readowl.notification.application.NotificationService;
import br.com.readowl.readowl.shared.api.PageRequests;
import br.com.readowl.readowl.shared.api.PageResponse;
import br.com.readowl.readowl.user.application.SessionUser;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.Set;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/notifications")
class NotificationController {

    private final NotificationService notifications;

    NotificationController(NotificationService notifications) {
        this.notifications = notifications;
    }

    record MarkRequest(@NotNull Boolean read) {
    }

    @GetMapping
    PageResponse<NotificationService.NotificationView> list(@AuthenticationPrincipal SessionUser principal,
                                                            @RequestParam(defaultValue = "false") boolean unreadOnly,
                                                            @RequestParam(defaultValue = "0") int page,
                                                            @RequestParam(defaultValue = "20") int size) {
        return notifications.list(principal, unreadOnly, PageRequests.of(page, size, "-createdAt", Set.of("createdAt")));
    }

    @GetMapping("/unread-count")
    NotificationService.UnreadCount unread(@AuthenticationPrincipal SessionUser principal) {
        return notifications.unread(principal);
    }

    @PatchMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void mark(@AuthenticationPrincipal SessionUser principal, @PathVariable UUID id,
              @Valid @RequestBody MarkRequest body) {
        notifications.markRead(id, body.read(), principal);
    }

    @PostMapping("/read-all")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void markAll(@AuthenticationPrincipal SessionUser principal) {
        notifications.markAllRead(principal);
    }
}
