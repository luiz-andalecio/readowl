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
            case EMAIL_CHANGE_CONFIRMATION -> {
                message.setSubject("Confirme seu novo e-mail no Readowl");
                message.setText("""
                        Para usar este endereço na sua conta do Readowl, abra o link abaixo. Ele vale por 1 hora e só pode ser usado uma vez.

                        %s

                        Se você não pediu esta alteração, ignore esta mensagem.
                        """.formatted(properties.pageUrl("/confirmar-email#token=" + token)));
            }
            case PASSWORD_CHANGED -> {
                message.setSubject("Sua senha do Readowl foi alterada");
                message.setText(securityNotice("A senha da sua conta no Readowl acabou de ser alterada."));
            }
            case GOOGLE_LINKED -> {
                message.setSubject("Uma conta do Google foi vinculada ao seu Readowl");
                message.setText(securityNotice("Uma conta do Google acabou de ser vinculada à sua conta no Readowl."));
            }
            case EMAIL_CHANGED -> {
                message.setSubject("O e-mail da sua conta do Readowl foi alterado");
                message.setText(securityNotice("O e-mail da sua conta no Readowl acabou de ser alterado, e este endereço não vale mais para entrar."));
            }
            case ACCOUNT_DELETED -> {
                message.setSubject("Sua conta do Readowl foi excluída");
                message.setText("""
                        Sua conta no Readowl foi excluída, como você pediu. Essa ação não pode ser desfeita.

                        Seus comentários continuam publicados sem o seu nome, e as obras saíram do ar.
                        """);
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

    private String securityNotice(String what) {
        return """
                %s

                Se foi você, não precisa fazer nada. Se não foi, redefina sua senha agora:

                %s
                """.formatted(what, properties.pageUrl("/recuperar-senha"));
    }
}
