package br.com.readowl.readowl.auth.application;

import br.com.readowl.readowl.user.application.UserAccessChanged;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

@Component
class UserAccessChangedListener {

    private final SessionManager sessions;

    UserAccessChangedListener(SessionManager sessions) {
        this.sessions = sessions;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    void revokeSessions(UserAccessChanged event) {
        sessions.revokeAll(event.userId());
    }
}
