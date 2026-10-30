package br.com.readowl.readowl;

import br.com.readowl.readowl.support.IntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.session.SessionRepository;
import org.springframework.session.jdbc.JdbcIndexedSessionRepository;

import static org.assertj.core.api.Assertions.assertThat;

@IntegrationTest
class ReadowlApplicationTests {

	@Autowired
	private SessionRepository<?> sessionRepository;

	@Test
	void contextLoadsWithJdbcSessions() {
		assertThat(sessionRepository).isInstanceOf(JdbcIndexedSessionRepository.class);
	}

}
