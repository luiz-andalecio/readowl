package br.com.readowl.readowl.auth;

import br.com.readowl.readowl.auth.application.AccountMails;
import br.com.readowl.readowl.auth.infrastructure.KnownDevices;
import br.com.readowl.readowl.support.ApiIntegrationTest;
import br.com.readowl.readowl.support.SqlFixtures;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * As filas e os navegadores conhecidos guardam dados de pessoas: cada um precisa ter um prazo que funcione.
 */
@ApiIntegrationTest
class PersonalDataPurgeIntegrationTest {

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private AccountMails mails;

    @Autowired
    private KnownDevices devices;

    private void failedMail(String email, String failedAgo) {
        jdbc.update("""
                INSERT INTO account_mail_queue (id, type, email, created_at, next_attempt_at, attempts, failed_at)
                VALUES (?, 'VERIFY_EMAIL', ?, now(), now(), 5, now() - ?::interval)
                """, UUID.randomUUID(), email, failedAgo);
    }

    private int mailsTo(String email) {
        return jdbc.queryForObject("SELECT count(*) FROM account_mail_queue WHERE email = ?", Integer.class, email);
    }

    private int devicesOf(UUID user) {
        return jdbc.queryForObject("SELECT count(*) FROM known_devices WHERE user_id = ?", Integer.class, user);
    }

    @Test
    void aFailedMailIsKeptForAWeekAndThenItsAddressIsDeleted() {
        String old = UUID.randomUUID() + "@example.com";
        String recent = UUID.randomUUID() + "@example.com";
        failedMail(old, "8 days");
        failedMail(recent, "1 day");

        mails.purgeFailed();

        assertThat(mailsTo(old)).isZero();
        assertThat(mailsTo(recent)).isEqualTo(1);
    }

    @Test
    void expiredBrowsersAreForgotten() {
        UUID user = new SqlFixtures(jdbc).user();
        String kept = devices.register(user);
        jdbc.update("""
                INSERT INTO known_devices (token_hash, user_id, created_at, expires_at)
                VALUES (?, ?, now() - interval '100 days', now() - interval '10 days')
                """, UUID.randomUUID().toString(), user);

        devices.purgeExpired();

        assertThat(devicesOf(user)).isEqualTo(1);
        assertThat(devices.isKnown(kept, user)).isTrue();
    }

    @Test
    void onlyTheTenNewestBrowsersAreKept() {
        UUID user = new SqlFixtures(jdbc).user();

        for (int device = 0; device < 12; device++) {
            devices.register(user);
        }

        assertThat(devicesOf(user)).isEqualTo(10);
    }
}
