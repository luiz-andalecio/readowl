package br.com.readowl.readowl.auth.infrastructure;

import jakarta.mail.MessagingException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.mail.javamail.JavaMailSenderImpl;

import static org.assertj.core.api.Assertions.assertThat;

@ExtendWith(OutputCaptureExtension.class)
class MailConnectionCheckTest {

    private final MailConnectionCheck check = new MailConnectionCheck(null);

    @Test
    void aFailureIsReportedWithoutStoppingTheApplicationOrShowingCredentials(CapturedOutput output) {
        check.check(new JavaMailSenderImpl() {
            @Override
            public void testConnection() throws MessagingException {
                throw new MessagingException("usuario@example.com recusado");
            }
        });

        assertThat(output).contains("Nao foi possivel conectar ao servidor de e-mail").contains("MessagingException")
                .doesNotContain("usuario@example.com");
    }

    @Test
    void aWorkingServerIsConfirmed(CapturedOutput output) {
        check.check(new JavaMailSenderImpl() {
            @Override
            public void testConnection() {
                // Conecta sem erro.
            }
        });

        assertThat(output).contains("Conexao com o servidor de e-mail confirmada");
    }
}
