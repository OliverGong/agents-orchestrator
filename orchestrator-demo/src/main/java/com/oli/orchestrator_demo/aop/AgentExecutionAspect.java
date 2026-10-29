/**
 * @ClassName AgentExecutionAspect
 * @Description Cross-cutting agent instrumentation (AOP): measures the
 *              execution time of every {@link Agent} invocation — independent
 *              of which collaboration template scheduled it — and reports
 *              failures with their duration. Beyond logging it also produces
 *              the two portable signals of the observability stack: a
 *              Micrometer {@link Observation} (exported as an OpenTelemetry
 *              span to Grafana Tempo, tagged with the agent name) and a
 *              {@code agent.execution.duration} {@link Timer} (scraped by
 *              Prometheus, tagged with agent and outcome). Extracted from the
 *              agents and the orchestrator so the business components stay
 *              free of timing, logging and metric noise.
 * @Author OliverGong
 * @Date Oct 29, 2026
 * @Version 1.0
 */
package com.oli.orchestrator_demo.aop;

import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.oli.orchestrator_demo.agent.Agent;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;

@Aspect
@Component
public class AgentExecutionAspect {

  private static final Logger log = LoggerFactory.getLogger(AgentExecutionAspect.class);

  static final String SPAN_NAME = "agent.execute";
  static final String METRIC_NAME = "agent.execution.duration";

  private final ObservationRegistry observationRegistry;
  private final MeterRegistry meterRegistry;

  public AgentExecutionAspect(ObservationRegistry observationRegistry, MeterRegistry meterRegistry) {
    this.observationRegistry = observationRegistry;
    this.meterRegistry = meterRegistry;
  }

  @Around("execution(* com.oli.orchestrator_demo.agent.Agent+.execute(..))")
  public Object measureExecutionTime(ProceedingJoinPoint joinPoint) throws Throwable {
    Agent<?, ?> agent = (Agent<?, ?>) joinPoint.getTarget();
    String name = agent.name();
    Observation observation = Observation.createNotStarted(SPAN_NAME, observationRegistry)
        .lowCardinalityKeyValue("agent", name)
        .start();
    Timer.Sample sample = Timer.start(meterRegistry);
    long startTime = System.currentTimeMillis();
    try {
      Object result = joinPoint.proceed();
      log.info("{} completed in {} ms", name, System.currentTimeMillis() - startTime);
      observation.stop();
      sample.stop(timer(name, "success"));
      return result;
    }
    catch (Throwable ex) {
      log.warn("{} failed after {} ms: {}", name,
          System.currentTimeMillis() - startTime, ex.getMessage());
      observation.error(ex);
      observation.stop();
      sample.stop(timer(name, "failure"));
      throw ex;
    }
  }

  private Timer timer(String agent, String outcome) {
    return Timer.builder(METRIC_NAME)
        .description("Duration of agent executions")
        .tag("agent", agent)
        .tag("outcome", outcome)
        .register(meterRegistry);
  }
}
