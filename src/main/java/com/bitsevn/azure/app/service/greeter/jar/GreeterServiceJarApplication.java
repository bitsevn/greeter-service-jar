package com.bitsevn.azure.app.service.greeter.jar;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class GreeterServiceJarApplication {

	public static void main(String[] args) {
		System.out.println("[greeter] [service] App is starting..");
		SpringApplication.run(GreeterServiceJarApplication.class, args);
		System.out.println("[greeter] [service] App is started.");
	}

}
