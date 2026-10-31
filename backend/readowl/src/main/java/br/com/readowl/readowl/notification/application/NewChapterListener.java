package br.com.readowl.readowl.notification.application;

import br.com.readowl.readowl.notification.infrastructure.NotificationQueue;
import br.com.readowl.readowl.publishing.application.ChapterFirstPublished;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Roda na transação da publicação: o evento só existe se ela for confirmada, e nunca se perde depois dela.
 * A publicação não espera por notificações nem e-mails.
 */
@Component
class NewChapterListener {

    private final NotificationQueue queue;

    NewChapterListener(NotificationQueue queue) {
        this.queue = queue;
    }

    @EventListener
    void enqueue(ChapterFirstPublished event) {
        queue.enqueueChapterPublished(event.bookId(), event.chapterId());
    }
}
