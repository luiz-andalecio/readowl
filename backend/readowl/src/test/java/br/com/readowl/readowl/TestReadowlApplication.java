package br.com.readowl.readowl;

import br.com.readowl.readowl.support.TestDatabaseConfiguration;
import org.springframework.boot.SpringApplication;

public class TestReadowlApplication {

	public static void main(String[] args) {
		SpringApplication.from(ReadowlApplication::main)
				.with(TestDatabaseConfiguration.class)
				.withAdditionalProfiles("test")
				.run(args);
	}

}
