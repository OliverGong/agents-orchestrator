/**
 * @ClassName UsageCaptureAdvisor
 * @Description Captures the LM usage of every model call (prompt/completion/
 *              total tokens and the model that served it) from the raw
 *              {@link ChatResponse} metadata into the
 *              {@link LmUsageCollector} thread-local, so the orchestrator can
 *              attach the aggregated consumption to the stage's observability
 *              event and the durable metrics table. Registered as the last
 *              custom advisor ({@code LOWEST_PRECEDENCE - 1}, strictly before
 *              the terminal {@code ChatModelCallAdvisor}): it sees the
 *              untouched backend response <em>before</em> the sanitizer and
 *              calibration advisors potentially rebuild it (their mutated
 *              responses drop the metadata, which is exactly why capture must
 *              happen closest to the model). Null-safe everywhere — a backend
 *              without usage reporting or a call outside a stage contributes
 *              nothing.
 * @Author OliverGong
 * @Date Nov 1, 2026
 * @Version 1.0
 */
package com.oli.orchestrator_demo.agent.advisor;

import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.CallAdvisor;
import org.springframework.ai.chat.client.advisor.api.CallAdvisorChain;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.core.Ordered;

import com.oli.orchestrator_demo.observability.metrics.LmUsageCollector;

public class UsageCaptureAdvisor implements CallAdvisor {

  public static final String NAME = "UsageCapture";

  @Override
  public String getName() {
    return NAME;
  }

  @Override
  public int getOrder() {
    // The LAST custom advisor before the model: one step below the terminal
    // ChatModelCallAdvisor's plain LOWEST_PRECEDENCE — an exact tie would
    // drop this advisor from the chain and lose the usage entirely.
    return Ordered.LOWEST_PRECEDENCE - 1;
  }

  @Override
  public ChatClientResponse adviseCall(ChatClientRequest request, CallAdvisorChain chain) {
    ChatClientResponse response = chain.nextCall(request);
    ChatResponse chatResponse = response.chatResponse();
    if (chatResponse == null || chatResponse.getMetadata() == null) {
      return response;
    }
    Usage usage = chatResponse.getMetadata().getUsage();
    if (usage != null) {
      LmUsageCollector.record(usage.getPromptTokens(), usage.getCompletionTokens(), usage.getTotalTokens(),
          chatResponse.getMetadata().getModel());
    }
    return response;
  }
}
