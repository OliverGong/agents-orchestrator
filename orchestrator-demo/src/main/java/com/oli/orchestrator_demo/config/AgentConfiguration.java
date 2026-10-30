/**
 * @ClassName AgentConfiguration
 * @Description Configuration activation for the agent stack: since control
 *              plane v1 the agents no longer own compile-time ChatClient
 *              beans with baked-in personas — they build their clients on the
 *              backend {@link org.springframework.ai.chat.model.ChatModel}
 *              selected through `spring.ai.model.chat` and read the system
 *              prompt / model / temperature / calibration rules from the
 *              control-plane registry ({@link com.oli.orchestrator_demo.control.AgentConfigRegistry})
 *              on every call. The built-in personas that seed that registry
 *              live in {@link com.oli.orchestrator_demo.control.AgentDefaults}.
 * @Author OliverGong
 * @Date Oct 29, 2026
 * @Version 1.1
 */
package com.oli.orchestrator_demo.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import com.oli.orchestrator_demo.workflow.WorkflowProperties;

@Configuration
@EnableConfigurationProperties({AgentModelProperties.class, WorkflowProperties.class})
public class AgentConfiguration {
}
