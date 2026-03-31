package com.cloudsync.agent;

import java.util.List;

/**
 * Abstraction over an LLM that supports function/tool calling.
 * // OPEN: Choose provider – OpenAI, Ollama, local model, etc.
 */
public interface LlmProvider {

    /**
     * Given the conversation history and available tool definitions, return the next
     * action the agent should take.
     *
     * @param systemPrompt  the agent's system prompt
     * @param history       list of prior messages (role + content pairs as strings)
     * @param tools         available tools
     * @return              the agent's next action
     */
    AgentAction nextAction(String systemPrompt, List<String> history, List<AgentTool> tools);

    /**
     * Represents the LLM's decision: either call a tool or produce a final answer.
     */
    record AgentAction(boolean isFinalAnswer, String toolName, String toolArgument, String finalMessage) {

        public static AgentAction callTool(String toolName, String toolArgument) {
            return new AgentAction(false, toolName, toolArgument, null);
        }

        public static AgentAction finalAnswer(String message) {
            return new AgentAction(true, null, null, message);
        }
    }
}
