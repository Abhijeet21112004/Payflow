package com.samplemerchant;

import java.util.TimeZone;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * A pretend shop's server. Its only job here is to receive PayFlow webhooks correctly:
 * verify they really came from PayFlow, and process each event exactly once.
 */
@SpringBootApplication
public class SampleMerchantApplication {

	public static void main(String[] args) {
		// All time handling is in UTC, independent of the machine's local zone.
		TimeZone.setDefault(TimeZone.getTimeZone("UTC"));
		SpringApplication.run(SampleMerchantApplication.class, args);
	}

}
