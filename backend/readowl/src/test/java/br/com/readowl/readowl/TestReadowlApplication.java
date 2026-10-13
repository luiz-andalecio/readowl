package br.com.readowl.readowl;

import org.springframework.boot.SpringApplication;

public class TestReadowlApplication {

	public static void main(String[] args) {
		SpringApplication.from(ReadowlApplication::main).with(TestcontainersConfiguration.class).run(args);
	}

}
