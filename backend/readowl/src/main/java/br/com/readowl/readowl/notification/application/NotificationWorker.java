package br.com.readowl.readowl.notification.application;

import br.com.readowl.readowl.notification.infrastructure.NotificationQueue;
import br.com.readowl.readowl.notification.infrastructure.NotificationQueue.PendingEmail;
import br.com.readowl.readowl.shared.config.ReadowlProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.mail.MailException;
import org.springframework.mail.MailSender;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionOperations;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Transforma eventos em notificações e envia os e-mails. Cada instância processa um item por vez, e cada tarefa
 * tem tentativas limitadas: depois disso, a falha fica registrada na própria linha e não é mais repetida.
 */
@Component
public class NotificationWorker {

    private static final Logger log = LoggerFactory.getLogger(NotificationWorker.class);
    private static final int MAX_EVENTS_PER_RUN = 100;
    private static final int EMAIL_BATCH_SIZE = 20;
    private static final int MAX_EMAIL_BATCHES_PER_RUN = 5;
    private static final int MAX_ATTEMPTS = 3;

    private final NotificationQueue queue;
    private final MailSender mailSender;
    private final ReadowlProperties properties;
    private final TransactionOperations transaction;

    NotificationWorker(NotificationQueue queue, MailSender mailSender, ReadowlProperties properties,
                       TransactionOperations transaction) {
        this.queue = queue;
        this.mailSender = mailSender;
        this.properties = properties;
        this.transaction = transaction;
    }

    @Scheduled(fixedDelayString = "${readowl.notifications.poll-interval}",
            initialDelayString = "${readowl.notifications.poll-interval}")
    public void run() {
        processEvents();
        deliverEmails();
    }

    public void processEvents() {
        for (int count = 0; count < MAX_EVENTS_PER_RUN; count++) {
            if (!processNextEvent()) {
                return;
            }
        }
    }

    public void deliverEmails() {
        for (int batch = 0; batch < MAX_EMAIL_BATCHES_PER_RUN; batch++) {
            Integer sent = transaction.execute(status -> {
                List<PendingEmail> emails = queue.claimEmails(EMAIL_BATCH_SIZE);
                emails.forEach(this::deliver);
                return emails.size();
            });
            if (sent == null || sent < EMAIL_BATCH_SIZE) {
                return;
            }
        }
    }

    @Scheduled(fixedDelayString = "PT24H", initialDelayString = "PT1H")
    public void purgeProcessedEvents() {
        queue.purgeProcessedEvents();
    }

    /**
     * A falha é gravada em outra transação, porque a do evento é desfeita.
     *
     * @return false quando a fila acabou
     */
    private boolean processNextEvent() {
        AtomicReference<UUID> current = new AtomicReference<>();
        try {
            return Boolean.TRUE.equals(transaction.execute(status -> {
                Optional<UUID> event = queue.claimNextEvent();
                if (event.isEmpty()) {
                    return false;
                }
                current.set(event.get());
                queue.fanOut(event.get());
                queue.markEventProcessed(event.get());
                return true;
            }));
        } catch (RuntimeException ex) {
            if (current.get() == null) {
                throw ex;
            }
            log.error("Falha ao processar evento de notificacao: id={}", current.get(), ex);
            queue.recordEventFailure(current.get(), ex.getClass().getSimpleName(), MAX_ATTEMPTS);
            return true;
        }
    }

    /**
     * Pode repetir um e-mail se a aplicação cair entre o envio e o commit. Perder o aviso seria pior.
     */
    private void deliver(PendingEmail email) {
        if (!email.deliverable()) {
            queue.markEmail(email.id(), "SKIPPED");
            return;
        }
        try {
            mailSender.send(message(email));
            queue.markEmail(email.id(), "SENT");
        } catch (MailException ex) {
            // Sem o endereço ou a mensagem da exceção, que pode citar o destinatário.
            log.warn("Falha ao enviar e-mail de novo capitulo: id={}, causa={}", email.id(), ex.getClass().getSimpleName());
            queue.recordEmailFailure(email.id(), ex.getClass().getSimpleName(), MAX_ATTEMPTS);
        }
    }

    /**
     * O assunto é fixo: títulos vêm dos autores e não devem chegar aos cabeçalhos da mensagem.
     */
    private SimpleMailMessage message(PendingEmail email) {
        SimpleMailMessage message = new SimpleMailMessage();
        message.setFrom(properties.mailFrom());
        message.setTo(email.to());
        message.setSubject("Novo capítulo em uma obra que você segue");
        message.setText("""
                Saiu um novo capítulo em uma obra que você segue no Readowl.

                Obra: %s
                Capítulo: %s

                %s

                Para não receber mais estes e-mails, desative a opção em %s.
                """.formatted(email.bookTitle(), email.chapterTitle(),
                properties.pageUrl("/obras/" + email.bookSlug() + "/capitulos/" + email.chapterSlug()),
                properties.pageUrl("/conta")));
        return message;
    }
}
