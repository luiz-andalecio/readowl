package br.com.readowl.readowl.auth.infrastructure;

import br.com.readowl.readowl.auth.application.AccountMailType;
import br.com.readowl.readowl.shared.config.ReadowlProperties;
import org.springframework.mail.MailSender;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.stereotype.Component;

/**
 * O token vai no fragmento do link, que o navegador não envia ao servidor e portanto não aparece em logs de acesso.
 */
@Component
public class AccountMailer {

    private final MailSender mailSender;
    private final ReadowlProperties properties;

    AccountMailer(MailSender mailSender, ReadowlProperties properties) {
        this.mailSender = mailSender;
        this.properties = properties;
    }

    /**
     * @param token token em claro, ausente nos avisos que não têm link de uso único
     */
    public void send(AccountMailType type, String to, String token) {
        SimpleMailMessage message = new SimpleMailMessage();
        message.setFrom(properties.mailFrom());
        message.setTo(to);
        switch (type) {
            case VERIFY_EMAIL -> {
                message.setSubject("Confirme seu e-mail no Readowl");
                message.setText("""
                        Para confirmar seu e-mail e ativar sua conta no Readowl, abra o link abaixo. Ele vale por 24 horas.

                        %s

                        Se você não criou esta conta, ignore esta mensagem e não abra o link.
                        """.formatted(properties.pageUrl("/verificar-email#token=" + token)));
            }
            case RESET_PASSWORD -> {
                message.setSubject("Redefina sua senha no Readowl");
                message.setText("""
                        Para definir uma nova senha, abra o link abaixo. Ele vale por 1 hora e só pode ser usado uma vez.

                        %s

                        Se você não pediu esta alteração, ignore esta mensagem: sua senha continua a mesma.
                        """.formatted(properties.pageUrl("/redefinir-senha#token=" + token)));
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
        mailSender.send(message);
    }
}
