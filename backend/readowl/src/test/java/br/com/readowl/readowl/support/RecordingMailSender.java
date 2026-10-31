package br.com.readowl.readowl.support;

import org.springframework.mail.MailSendException;
import org.springframework.mail.MailSender;
import org.springframework.mail.SimpleMailMessage;

import java.time.Duration;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Guarda as mensagens em memória, por destinatário. O envio real é assíncrono, então os testes
 * aguardam a chegada da mensagem.
 */
public class RecordingMailSender implements MailSender {

    private static final Duration WAIT = Duration.ofSeconds(5);
    private static final Pattern TOKEN = Pattern.compile("#token=([A-Za-z0-9_-]+)");

    private final Map<String, BlockingQueue<SimpleMailMessage>> inboxes = new ConcurrentHashMap<>();
    private volatile boolean failing;

    @Override
    public void send(SimpleMailMessage message) {
        if (failing) {
            throw new MailSendException("SMTP simulado indisponível");
        }
        for (String recipient : Objects.requireNonNull(message.getTo())) {
            inbox(recipient).add(message);
        }
    }

    @Override
    public void send(SimpleMailMessage... messages) {
        for (SimpleMailMessage message : messages) {
            send(message);
        }
    }

    public void setFailing(boolean failing) {
        this.failing = failing;
    }

    public SimpleMailMessage awaitMessage(String recipient) {
        try {
            SimpleMailMessage message = inbox(recipient).poll(WAIT.toMillis(), TimeUnit.MILLISECONDS);
            if (message == null) {
                throw new AssertionError("Nenhum e-mail recebido por " + recipient);
            }
            return message;
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new AssertionError(ex);
        }
    }

    public String awaitToken(String recipient) {
        Matcher matcher = TOKEN.matcher(Objects.requireNonNull(awaitMessage(recipient).getText()));
        if (!matcher.find()) {
            throw new AssertionError("E-mail sem token");
        }
        return matcher.group(1);
    }

    /**
     * Confirma a ausência de mensagens depois de dar tempo para o envio assíncrono acontecer.
     */
    public boolean receivesNothing(String recipient) throws InterruptedException {
        return inbox(recipient).poll(500, TimeUnit.MILLISECONDS) == null;
    }

    private BlockingQueue<SimpleMailMessage> inbox(String recipient) {
        return inboxes.computeIfAbsent(recipient, key -> new LinkedBlockingQueue<>());
    }
}
