package br.com.readowl.readowl.auth.infrastructure;

import jakarta.mail.MessagingException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.mail.MailSender;
import org.springframework.mail.javamail.JavaMailSenderImpl;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

/**
 * Sem e-mail não há confirmação, e sem confirmação ninguém entra. Uma credencial errada apareceria só como aviso
 * a cada cadastro, então é conferida ao subir, sem atrasar nem impedir a inicialização.
 */
@Component
class MailConnectionCheck {

    private static final Logger log = LoggerFactory.getLogger(MailConnectionCheck.class);

    private final ObjectProvider<MailSender> mailSender;

    MailConnectionCheck(ObjectProvider<MailSender> mailSender) {
        this.mailSender = mailSender;
    }

    @Async
    @EventListener(ApplicationReadyEvent.class)
    public void check() {
        if (mailSender.getIfAvailable() instanceof JavaMailSenderImpl smtp) {
            check(smtp);
        }
    }

    void check(JavaMailSenderImpl smtp) {
        try {
            smtp.testConnection();
            log.info("Conexao com o servidor de e-mail confirmada");
        } catch (MessagingException | RuntimeException ex) {
            log.error("Nao foi possivel conectar ao servidor de e-mail: os e-mails de conta nao serao entregues. causa={}",
                    ex.getClass().getSimpleName());
        }
    }
}
