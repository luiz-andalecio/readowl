package br.com.readowl.readowl;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

@Import(TestcontainersConfiguration.class)
@SpringBootTest
class ReadowlApplicationTests {

	@Test
	void contextLoads() {
	}

}
