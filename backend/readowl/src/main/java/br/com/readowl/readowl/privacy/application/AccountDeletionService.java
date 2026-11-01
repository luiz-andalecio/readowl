package br.com.readowl.readowl.privacy.application;

import br.com.readowl.readowl.auth.application.AccountConfirmation;
import br.com.readowl.readowl.auth.application.AccountMailType;
import br.com.readowl.readowl.auth.application.AccountMails;
import br.com.readowl.readowl.auth.application.SessionManager;
import br.com.readowl.readowl.shared.audit.AuditTrail;
import br.com.readowl.readowl.shared.audit.SecurityEvents;
import br.com.readowl.readowl.user.application.SessionUser;
import br.com.readowl.readowl.user.application.UserService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionOperations;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Exclusão da conta pela própria pessoa, sem volta. A linha em users continua, sem dados pessoais, porque comentários,
 * denúncias e a auditoria apontam para ela. Os comentários ficam, com o texto e sem o autor, para não quebrar conversas.
 * <p>
 * Os prazos do que continua guardado estão em docs/personal-data.md. Um teste confere que toda tabela que aponta para
 * users está nesta lista ou em {@link #KEPT_TABLES}: uma tabela nova obriga a decidir.
 */
@Service
public class AccountDeletionService {

    /**
     * Dados da própria pessoa, removidos com a conta.
     */
    public static final List<String> REMOVED_TABLES = List.of("book_follows", "book_ratings", "chapter_reactions",
            "comment_likes", "chapter_views", "reading_progress", "notifications", "accounts", "verification_tokens",
            "password_reset_tokens", "email_change_tokens", "known_devices", "account_mail_queue");

    /**
     * Continuam apontando para a linha anonimizada. chapter_revisions e books têm o próprio tratamento: as obras saem
     * com as revisões, e uma revisão publicada em obra de outra pessoa pertence a essa obra.
     */
    public static final List<String> KEPT_TABLES = List.of("comments", "reports", "audit_log", "books", "chapter_revisions");

    private final UserService users;
    private final AccountConfirmation confirmation;
    private final AccountMails mails;
    private final SessionManager sessions;
    private final AuditTrail audit;
    private final SecurityEvents events;
    private final RetainedBooks books;
    private final JdbcTemplate jdbc;
    private final TransactionOperations transaction;

    AccountDeletionService(UserService users, AccountConfirmation confirmation, AccountMails mails, SessionManager sessions,
                           AuditTrail audit, SecurityEvents events, RetainedBooks books, JdbcTemplate jdbc,
                           TransactionOperations transaction) {
        this.users = users;
        this.confirmation = confirmation;
        this.mails = mails;
        this.sessions = sessions;
        this.audit = audit;
        this.events = events;
        this.books = books;
        this.jdbc = jdbc;
        this.transaction = transaction;
    }

    public void delete(SessionUser principal, String password, HttpServletRequest request, HttpServletResponse response) {
        confirmation.require(principal, password);
        UUID id = principal.id();
        transaction.executeWithoutResult(status -> {
            String email = users.anonymize(id, Instant.now());
            for (String table : REMOVED_TABLES) {
                jdbc.update("DELETE FROM " + table + " WHERE user_id = ?", id);
            }
            jdbc.update("DELETE FROM account_mail_queue WHERE email = ?", email);
            books.deleteUnreportedOf(id);
            audit.record(id, "ACCOUNT_DELETED", "users/" + id, Map.of());
            mails.request(AccountMailType.ACCOUNT_DELETED, email);
        });
        sessions.revokeAllExceptCurrent(id, request);
        sessions.terminate(request, response);
        events.record(SecurityEvents.Event.ACCOUNT_DELETED, id);
    }
}
