package com.payflow;

import org.springframework.boot.SpringApplication;

public class TestPayflowApiApplication {

	public static void main(String[] args) {
		SpringApplication.from(PayflowApiApplication::main).with(TestcontainersConfiguration.class).run(args);
	}

}
