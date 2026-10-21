package com.samplemerchant;

import org.springframework.boot.SpringApplication;

public class TestSampleMerchantApplication {

	public static void main(String[] args) {
		SpringApplication.from(SampleMerchantApplication::main).with(TestcontainersConfiguration.class).run(args);
	}

}
