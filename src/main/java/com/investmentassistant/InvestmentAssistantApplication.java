package com.investmentassistant;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class InvestmentAssistantApplication {

    public static void main(String[] args) {
        SpringApplication.run(InvestmentAssistantApplication.class, args);
    }
}
