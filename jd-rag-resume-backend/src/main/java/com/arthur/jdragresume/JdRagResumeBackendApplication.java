package com.arthur.jdragresume;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class JdRagResumeBackendApplication {

	public static void main(String[] args) {
		SpringApplication.run(JdRagResumeBackendApplication.class, args);
	}

}
