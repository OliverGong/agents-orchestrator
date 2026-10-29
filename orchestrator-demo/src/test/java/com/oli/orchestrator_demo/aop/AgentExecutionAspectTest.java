/**
 * @ClassName AgentExecutionAspectTest
 * @Description Verifies the agent instrumentation aspect: successful
 *              executions pass through and report their duration, failures
 *              are logged and rethrown unchanged, and every execution produces
 *              the observability signals (a Micrometer observation/span tagged
 *              with the agent name plus an agent.execution.duration timer with
 *              the outcome tag).
 * @Author OliverGong
 * @Date Oct 29, 2026
 * @Version 1.0
 */
package com.oli.orchestrator_demo.aop;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.aop.aspectj.annotation.AspectJProxyFactory;

import com.oli.orchestrator_demo.agent.Agent;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationHandler;
import io.micrometer.observation.ObservationRegistry;

class AgentExecutionAspectTest {

  private final ListAppender<ILoggingEvent> logs = new ListAppender<>();
  private final SimpleMeterRegistry meters = new SimpleMeterRegistry();
  private final ObservationRegistry observations = ObservationRegistry.create();

  @BeforeEach
  void attachLogCaptor() {
    logs.start();
    ((Logger) LoggerFactory.getLogger(AgentExecutionAspect.class)).addAppender(logs);
  }

  @AfterEach
  void detachLogCaptor() {
    ((Logger) LoggerFactory.getLogger(AgentExecutionAspect.class)).detachAppender(logs);
  }

  @SuppressWarnings("unchecked")
  private Agent<String, String> proxied(Agent<String, String> agent) {
    AspectJProxyFactory factory = new AspectJProxyFactory(agent);
    factory.addAspect(new AgentExecutionAspect(observations, meters));
    return (Agent<String, String>) factory.getProxy();
  }

  @Test
  void logsDurationOfSuccessfulAgentExecution() {
    Agent<String, String> proxy = proxied(new Agent<>() {
      @Override
      public String name() {
        return "successful-agent";
      }

      @Override
      public String execute(String input) {
        return input.toUpperCase();
      }
    });

    String result = proxy.execute("hello");

    assertThat(result).isEqualTo("HELLO");
    assertThat(logs.list)
        .anySatisfy(event -> {
          assertThat(event.getLevel()).isEqualTo(Level.INFO);
          assertThat(event.getFormattedMessage())
              .contains("successful-agent")
              .contains("completed in");
        });
  }

  @Test
  void logsAndRethrowsAgentFailure() {
    Agent<String, String> proxy = proxied(new Agent<>() {
      @Override
      public String name() {
        return "failing-agent";
      }

      @Override
      public String execute(String input) {
        throw new IllegalStateException("boom");
      }
    });

    assertThatThrownBy(() -> proxy.execute("input"))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("boom");

    assertThat(logs.list)
        .anySatisfy(event -> {
          assertThat(event.getLevel()).isEqualTo(Level.WARN);
          assertThat(event.getFormattedMessage())
              .contains("failing-agent")
              .contains("failed after");
        });
    assertThat(meters.get(AgentExecutionAspect.METRIC_NAME)
        .tag("agent", "failing-agent")
        .tag("outcome", "failure")
        .timer()
        .count()).isEqualTo(1);
  }

  @Test
  void recordsObservationSpanAndTimerMetric() {
    List<Observation.Context> started = new ArrayList<>();
    List<Observation.Context> stopped = new ArrayList<>();
    observations.observationConfig().observationHandler(new ObservationHandler<Observation.Context>() {
      @Override
      public boolean supportsContext(Observation.Context context) {
        return true;
      }

      @Override
      public void onStart(Observation.Context context) {
        started.add(context);
      }

      @Override
      public void onStop(Observation.Context context) {
        stopped.add(context);
      }
    });

    Agent<String, String> proxy = proxied(new Agent<>() {
      @Override
      public String name() {
        return "metrics-agent";
      }

      @Override
      public String execute(String input) {
        return input;
      }
    });
    proxy.execute("hello");

    assertThat(started).hasSize(1);
    assertThat(started.get(0).getName()).isEqualTo(AgentExecutionAspect.SPAN_NAME);
    assertThat(started.get(0).getAllKeyValues())
        .anySatisfy(keyValue -> {
          assertThat(keyValue.getKey()).isEqualTo("agent");
          assertThat(keyValue.getValue()).isEqualTo("metrics-agent");
        });
    assertThat(stopped).hasSize(1);
    assertThat(meters.get(AgentExecutionAspect.METRIC_NAME)
        .tag("agent", "metrics-agent")
        .tag("outcome", "success")
        .timer()
        .count()).isEqualTo(1);
  }
}
