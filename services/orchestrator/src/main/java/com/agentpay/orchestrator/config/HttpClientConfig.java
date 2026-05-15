package com.agentpay.orchestrator.config;

import java.net.http.HttpClient;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

@Configuration
public class HttpClientConfig {

  // Pin to HTTP/1.1: the JDK HttpClient defaults to HTTP/2, which negotiates poorly with
  // plaintext servers like the mock-psp (and WireMock under the integration test) — handshake
  // fails with "EOF reached while reading". HTTP/1.1 is the right choice for orchestrator→PSP
  // anyway; nothing here benefits from HTTP/2.
  @Bean
  RestClient.Builder restClientBuilder() {
    HttpClient httpClient = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build();
    return RestClient.builder().requestFactory(new JdkClientHttpRequestFactory(httpClient));
  }
}
