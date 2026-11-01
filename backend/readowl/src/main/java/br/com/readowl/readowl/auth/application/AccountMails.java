package br.com.readowl.readowl.auth.application;

import br.com.readowl.readowl.auth.infrastructure.AccountMailQueue;
import br.com.readowl.readowl.auth.infrastructure.AccountMailQueue.PendingAccountMail;
import br.com.readowl.readowl.auth.infrastructure.AccountMailer;
import br.com.readowl.readowl.user.application.UserService;
import br.com.readowl.readowl.user.domain.User;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.transaction.support.TransactionOperations;

import java.util.List;
import java.util.Optional;

/**
 * E-mails de conta. O pedido é gravado na transação da operação, e o envio tenta sair logo depois do commit. Se o
 * SMTP falhar, a fila em memória encher ou a aplicação cair, a tarefa agendada tenta de novo.
 * <p>
 * O token nasce na hora do envio e nunca fica na fila. A entrega é ao menos uma vez: cada envio gera um token
 * novo e invalida o anterior, então um e-mail repetido não deixa dois links válidos.
 */
@Service
public class AccountMails {

    private static final Logger log = LoggerFactory.getLogger(AccountMails.class);
    private static final int BATCH_SIZE = 10;
    private static final int MAX_ATTEMPTS = 5;

    private final AccountMailQueue queue;
    private final AccountMailer mailer;
    private final AccountTokens tokens;
    private final UserService users;
    private final ApplicationEventPublisher events;
    private final TransactionOperations transaction;

    AccountMails(AccountMailQueue queue, AccountMailer mailer, AccountTokens tokens, UserService users,
                 ApplicationEventPublisher events, TransactionOperations transaction) {
        this.queue = queue;
        this.mailer = mailer;
        this.tokens = tokens;
        this.users = users;
        this.events = events;
        this.transaction = transaction;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void request(AccountMailType type, String email) {
        events.publishEvent(new AccountMailRequested(queue.enqueue(type, email)));
    }

    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onRequested(AccountMailRequested request) {
        queue.claim(request.id()).ifPresent(this::deliver);
    }

    @Scheduled(fixedDelayString = "${readowl.notifications.poll-interval}",
            initialDelayString = "${readowl.notifications.poll-interval}")
    public void deliverDue() {
        List<PendingAccountMail> due;
        do {
            due = queue.claimDue(BATCH_SIZE);
            due.forEach(this::deliver);
        } while (due.size() == BATCH_SIZE);
    }

    @Scheduled(fixedDelayString = "PT24H", initialDelayString = "PT1H")
    public void purgeFailed() {
        queue.purgeFailed();
    }

    private void deliver(PendingAccountMail mail) {
        try {
            Optional<Optional<String>> content = transaction.execute(status -> content(mail));
            if (content != null && content.isPresent()) {
                mailer.send(mail.type(), mail.email(), content.get().orElse(null));
            }
            queue.delete(mail.id());
        } catch (RuntimeException ex) {
            // Sem o endereço, o token ou a mensagem da exceção, que pode citar o destinatário.
            log.warn("Falha ao enviar e-mail de conta: id={}, tipo={}, causa={}", mail.id(), mail.type(),
                    ex.getClass().getSimpleName());
            queue.recordFailure(mail.id(), ex.getClass().getSimpleName(), MAX_ATTEMPTS);
        }
    }

    /**
     * A situação da conta pode ter mudado desde o pedido.
     *
     * @return vazio se não há mais o que enviar; do contrário, o token, quando o e-mail tem um
     */
    private Optional<Optional<String>> content(PendingAccountMail mail) {
        if (mail.type() == AccountMailType.ACCOUNT_ALREADY_EXISTS) {
            return Optional.of(Optional.empty());
        }
        Optional<User> user = users.findByEmail(mail.email()).filter(User::isActive);
        if (mail.type() == AccountMailType.VERIFY_EMAIL) {
            return user.filter(candidate -> !candidate.isEmailVerified())
                    .map(candidate -> Optional.of(tokens.issueVerification(candidate)));
        }
        return user.map(candidate -> Optional.of(tokens.issuePasswordReset(candidate)));
    }
}
