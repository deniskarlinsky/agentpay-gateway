package com.agentpay.buyer;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.ConfigurableApplicationContext;

@SpringBootApplication
public class BuyerClientApplication {

  public static void main(String[] args) {
    // SpringApplication.exit picks up the ExitCodeGenerator on BuyerClientRunner — a non-zero
    // code propagates out of `make demo` so a failing scenario fails the demo, not just logs.
    ConfigurableApplicationContext ctx = SpringApplication.run(BuyerClientApplication.class, args);
    System.exit(SpringApplication.exit(ctx));
  }
}
