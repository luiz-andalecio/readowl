package br.com.readowl.readowl.auth.infrastructure;

import br.com.readowl.readowl.auth.application.AccountMailRequested;
import br.com.readowl.readowl.shared.config.ReadowlProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.mail.MailException;
import org.springframework.mail.MailSender;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Envia fora da requisição: uma falha do SMTP não desfaz a operação, e o tempo de envio não revela
 * se a conta existe. Quem não receber pode pedir o reenvio. O token vai no fragmento do link, que
 * o navegador não envia ao servidor e portanto não aparece em logs de acesso.
 */
@Component
class AccountMailer {

    private static final Logger log = LoggerFactory.getLogger(AccountMailer.class);

    private final MailSender mailSender;
    private final ReadowlProperties properties;

    AccountMailer(MailSender mailSender, ReadowlProperties properties) {
        this.mailSender = mailSender;
        this.properties = properties;
    }

    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void send(AccountMailRequested request) {
        SimpleMailMessage message = new SimpleMailMessage();
        message.setFrom(properties.mailFrom());
        message.setTo(request.to());
        switch (request.type()) {
            case VERIFY_EMAIL -> {
                message.setSubject("Confirme seu e-mail no Readowl");
                message.setText("""
                        Para confirmar seu e-mail e ativar sua conta no Readowl, abra o link abaixo. Ele vale por 24 horas.

                        %s

                        Se você não criou esta conta, ignore esta mensagem e não abra o link.
                        """.formatted(properties.pageUrl("/verificar-email#token=" + request.token())));
            }
            case RESET_PASSWORD -> {
                message.setSubject("Redefina sua senha no Readowl");
                message.setText("""
                        Para definir uma nova senha, abra o link abaixo. Ele vale por 1 hora e só pode ser usado uma vez.

                        %s

                        Se você não pediu esta alteração, ignore esta mensagem: sua senha continua a mesma.
                        """.formatted(properties.pageUrl("/redefinir-senha#token=" + request.token())));
            }
            case ACCOUNT_ALREADY_EXISTS -> {
                message.setSubject("Tentativa de cadastro no Readowl");
                message.setText("""
                        Alguém tentou criar uma conta no Readowl com este e-mail, mas ele já está cadastrado.

                        Se foi você, recupere o acesso definindo uma nova senha em:

                        %s

                        Se não foi você, ignore esta mensagem.
                        """.formatted(properties.pageUrl("/recuperar-senha")));
            }
        }
        try {
            mailSender.send(message);
        } catch (MailException ex) {
            // Sem o endereço, o token ou a mensagem da exceção, que pode citar o destinatário.
            log.warn("Falha ao enviar e-mail de conta: tipo={}, causa={}",
                    request.type(), ex.getClass().getSimpleName());
        }
    }
}
