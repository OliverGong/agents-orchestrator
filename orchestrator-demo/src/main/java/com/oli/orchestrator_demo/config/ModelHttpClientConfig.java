/**
 * @ClassName ModelHttpClientConfig
 * @Description Binds explicit connect/read timeouts onto the shared
 *              {@link RestClient.Builder} that the Spring AI model
 *              auto-configuration (Ollama) builds its HTTP client from.
 *              Without them a hung or silently-stalled model backend blocks
 *              the calling thread forever: the workflow never returns and no
 *              exception ever reaches the caller. With them a stalled call
 *              surfaces as a ResourceAccessException (connect/read timeout),
 *              which the GlobalExceptionHandler maps to 503 so the caller
 *              always gets an answer.
 * @Author OliverGong
 * @Date Oct 29, 2026
 * @Version 1.0
 */
package com.oli.orchestrator_demo.config;

import java.time.Duration;

import org.springframework.boot.http.client.ClientHttpRequestFactoryBuilder;
import org.springframework.boot.http.client.HttpClientSettings;
import org.springframework.boot.restclient.RestClientCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class ModelHttpClientConfig {

  /** Connect timeout for reaching the model backend. */
  static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(5);

  /**
   * Idle-read timeout per model call. Non-streaming Ollama responses stay
   * silent until generation completes, and a local 7B model may legitimately
   * take minutes for a large JSON payload, so this must stay generous.
   */
  static final Duration READ_TIMEOUT = Duration.ofMinutes(5);

  @Bean
  RestClientCustomizer modelHttpTimeoutCustomizer() {
    HttpClientSettings settings = HttpClientSettings.defaults()
        .withConnectTimeout(CONNECT_TIMEOUT)
        .withReadTimeout(READ_TIMEOUT);
    return builder -> builder
        .requestFactory(ClientHttpRequestFactoryBuilder.detect().build(settings));
  }
}
