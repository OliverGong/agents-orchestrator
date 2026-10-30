/**
 * @ClassName CalibrationAdvisor
 * @Description Control-plane response calibration: applies the agent's
 *              user-defined {@link CalibrationRule}s (regex -> replacement)
 *              to the model output on every call. It is registered as the
 *              outermost advisor (order LOWEST_PRECEDENCE - 200), so on the
 *              response path it runs <em>after</em> the
 *              {@link ResponseSanitizerAdvisor} (order -100): the sanitizer
 *              first normalizes the payload (fence stripping, JSON
 *              extraction), then the calibration rules 规范化/格式化/校准 the
 *              clean text. A rule with an invalid regex is skipped with a
 *              warning — a configuration mistake must never fail a run.
 * @Author OliverGong
 * @Date Oct 30, 2026
 * @Version 1.0
 */
package com.oli.orchestrator_demo.agent;

import java.util.List;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.CallAdvisor;
import org.springframework.ai.chat.client.advisor.api.CallAdvisorChain;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.core.Ordered;

import com.oli.orchestrator_demo.control.AgentRuntimeStore;
import com.oli.orchestrator_demo.control.CalibrationRule;

public class CalibrationAdvisor implements CallAdvisor {

  public static final String NAME = "Calibration";

  private static final Logger log = LoggerFactory.getLogger(CalibrationAdvisor.class);

  private final String agentName;
  private final AgentRuntimeStore runtimeStore;

  public CalibrationAdvisor(String agentName, AgentRuntimeStore runtimeStore) {
    this.agentName = agentName;
    this.runtimeStore = runtimeStore;
  }

  @Override
  public String getName() {
    return NAME;
  }

  @Override
  public int getOrder() {
    // Strictly below the sanitizer's order (LOWEST_PRECEDENCE - 100): the
    // lower the order, the closer to the caller, i.e. the LATER the response
    // passes through — calibration must see the sanitized text.
    return Ordered.LOWEST_PRECEDENCE - 200;
  }

  @Override
  public ChatClientResponse adviseCall(ChatClientRequest request, CallAdvisorChain chain) {
    ChatClientResponse response = chain.nextCall(request);
    ChatResponse chatResponse = response.chatResponse();
    if (chatResponse == null || chatResponse.getResult() == null
        || chatResponse.getResult().getOutput() == null) {
      return response;
    }
    List<CalibrationRule> rules = runtimeStore.agent(agentName).calibrationRules();
    if (rules.isEmpty()) {
      return response;
    }
    String original = chatResponse.getResult().getOutput().getText();
    String calibrated = apply(agentName, original, rules);
    if (calibrated.equals(original)) {
      return response;
    }
    return response.mutate()
        .chatResponse(new ChatResponse(List.of(new Generation(new AssistantMessage(calibrated)))))
        .build();
  }

  /** Applies every rule in order; invalid patterns are skipped with a warning. */
  static String apply(String agentName, String text, List<CalibrationRule> rules) {
    if (text == null) {
      return text;
    }
    String current = text;
    for (CalibrationRule rule : rules) {
      if (rule.pattern() == null || rule.pattern().isBlank()) {
        continue;
      }
      try {
        current = Pattern.compile(rule.pattern())
            .matcher(current)
            .replaceAll(rule.replacement() == null ? "" : rule.replacement());
      }
      catch (RuntimeException ex) {
        log.warn("Skipping calibration rule '{}' of agent '{}': {}",
            rule.pattern(), agentName, ex.getMessage());
      }
    }
    return current;
  }
}
