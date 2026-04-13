package com.cloudsync.agent;

import com.cloudsync.model.dto.AgentStepEvent;
import io.micronaut.context.annotation.Value;
import jakarta.inject.Singleton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;
import reactor.core.publisher.FluxSink;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Iterative agent executor: drives the tool-calling loop and emits SSE events.
 */
@Singleton
public class AgentExecutor {

    private static final Logger LOG = LoggerFactory.getLogger(AgentExecutor.class);
    private static final int MAX_STEPS = 10;

    private final LlmProvider llmProvider;
    private final List<AgentTool> tools;
    private final String systemPrompt;

    public AgentExecutor(LlmProvider llmProvider,
                         List<AgentTool> tools,
                         @Value("${app.external-drive-path}") String externalDrivePath) {
        this.llmProvider = llmProvider;
        this.tools = tools;
        this.systemPrompt = """
                You are a disk detection agent running inside a Docker container on a Linux host.
                Your goal is to detect and verify an external drive is mounted at %s.
                Use the available tools step by step. Stop when you confirm the drive is mounted and accessible,
                or when you determine no suitable drive is available.
                Be concise. Prefer /dev/sd* or /dev/nvme* devices. Do not mount any device that hosts the root filesystem, /boot, or swap.
                """.formatted(externalDrivePath);
    }

    /**
     * Run the agent and return a Flux of SSE step events.
     */
    public Flux<AgentStepEvent> run() {
        return Flux.create(sink -> {
            Map<String, AgentTool> toolMap = tools.stream()
                    .collect(Collectors.toMap(AgentTool::getName, Function.identity()));

            List<String> history = new ArrayList<>();
            int step = 0;

            try {
                while (step < MAX_STEPS) {
                    step++;
                    LlmProvider.AgentAction action = llmProvider.nextAction(systemPrompt, history, tools);

                    if (action.isFinalAnswer()) {
                        sink.next(new AgentStepEvent("final", null, null, true, action.finalMessage()));
                        break;
                    }

                    AgentTool tool = toolMap.get(action.toolName());
                    if (tool == null) {
                        String msg = "Unknown tool: " + action.toolName();
                        LOG.warn(msg);
                        sink.next(new AgentStepEvent(step, action.toolName(), msg, false, null));
                        break;
                    }

                    String result = tool.execute(action.toolArgument());
                    LOG.debug("Step {}: tool={} arg={} result={}", step, action.toolName(), action.toolArgument(), result);

                    sink.next(new AgentStepEvent(step, action.toolName(), result, null, null));

                    history.add("Tool call: " + action.toolName() + "(" + action.toolArgument() + ")");
                    history.add("Result: " + result);
                }

                if (step >= MAX_STEPS) {
                    sink.next(new AgentStepEvent("final", null, null, false, "Max steps reached without resolution"));
                }
            } catch (Exception e) {
                LOG.error("Agent execution failed", e);
                sink.error(e);
                return;
            }

            sink.complete();
        });
    }
}
