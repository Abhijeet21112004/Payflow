package com.payflow;

import java.util.TimeZone;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class PayflowApiApplication {

	public static void main(String[] args) {
		// All time handling is in UTC, independent of the machine's local zone.
		// (Tests get the same setting via -Duser.timezone=UTC in pom.xml.)
		TimeZone.setDefault(TimeZone.getTimeZone("UTC"));
		SpringApplication.run(PayflowApiApplication.class, args);
	}

}
