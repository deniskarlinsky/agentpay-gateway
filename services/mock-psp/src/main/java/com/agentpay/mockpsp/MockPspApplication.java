package com.agentpay.mockpsp;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

@SpringBootApplication
@EnableConfigurationProperties(PspProperties.class)
public class MockPspApplication {

  public static void main(String[] args) {
    SpringApplication.run(MockPspApplication.class, args);
  }
}
