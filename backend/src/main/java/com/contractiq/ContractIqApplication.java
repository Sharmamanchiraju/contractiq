package com.contractiq;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Entry point of the ContractIQ backend; exists so Spring Boot can scan the feature packages.
 */
@SpringBootApplication
public class ContractIqApplication {

    public static void main(String[] args) {
        SpringApplication.run(ContractIqApplication.class, args);
    }
}
