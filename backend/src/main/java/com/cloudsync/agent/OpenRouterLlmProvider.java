package com.cloudsync.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.micronaut.context.annotation.Primary;
import io.micronaut.context.annotation.Requires;
import io.micronaut.context.annotation.Value;
import jakarta.inject.Singleton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;

/**
 * LLM provider backed by OpenRouter (OpenAI-compatible API).
 * Active only when OPENROUTER_API_KEY is set to a non-empty value.
 * Marked @Primary so it takes precedence over FallbackLlmProvider.
 *
 * Default model: google/gemini-2.0-flash-001 – cheap, fast, supports tool calling.
 */

@Singleton
@Primary
@Requires(property = "openrouter.api-key", notEquals = "")
public class OpenRouterLlmProvider implements LlmProvider {

    private static final Logger LOG = LoggerFactory.getLogger(OpenRouterLlmProvider.class);
    private static final String API_URL = "https://openrouter.ai/api/v1/chat/completions";

    private final String apiKey;
    private final LlmModel model;
    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;

    public OpenRouterLlmProvider(
            @Value("${openrouter.api-key}") String apiKey,
            @Value("${openrouter.model:google/gemini-2.0-flash-001}") String modelId) {
        this.apiKey = apiKey;
        this.model = new LlmModel(modelId);
        this.httpClient = HttpClient.newHttpClient();
        this.objectMapper = new ObjectMapper();
    }

    @Override
    public AgentAction nextAction(String systemPrompt, List<String> history, List<AgentTool> tools) {
        try {
            String requestBody = buildRequest(systemPrompt, history, tools);
            LOG.debug("OpenRouter request model={}: {}", model, requestBody);

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(API_URL))
                    .header("Authorization", "Bearer " + apiKey)
                    .header("Content-Type", "application/json")
                    .header("HTTP-Referer", "https://github.com/cloud-synchronizer")
                    .header("X-Title", "CloudSynchronizer DiskAgent")
                    .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            LOG.debug("OpenRouter response status={}: {}", response.statusCode(), response.body());

            if (response.statusCode() != 200) {
                LOG.error("OpenRouter API error {}: {}", response.statusCode(), response.body());
                return AgentAction.finalAnswer("LLM API error: " + response.statusCode());
            }

            return parseResponse(response.body());

        } catch (Exception e) {
            LOG.error("OpenRouter call failed", e);
            return AgentAction.finalAnswer("LLM call failed: " + e.getMessage());
        }
    }

    private String buildRequest(String systemPrompt, List<String> history, List<AgentTool> tools) throws Exception {
        ObjectNode body = objectMapper.createObjectNode();
        body.put("model", model.id());

        // Messages
        ArrayNode messages = body.putArray("messages");
        addMessage(messages, "system", systemPrompt);

        // History: alternating assistant/user (tool-call, tool-result pairs)
        for (int i = 0; i < history.size(); i++) {
            addMessage(messages, i % 2 == 0 ? "assistant" : "user", history.get(i));
        }

        // Tools
        ArrayNode toolsArray = body.putArray("tools");
        for (AgentTool tool : tools) {
            ObjectNode toolNode = toolsArray.addObject();
            toolNode.put("type", "function");
            ObjectNode func = toolNode.putObject("function");
            func.put("name", tool.getName());
            func.put("description", tool.getDescription());
            ObjectNode params = func.putObject("parameters");
            params.put("type", "object");
            ObjectNode props = params.putObject("properties");
            ObjectNode argProp = props.putObject("argument");
            argProp.put("type", "string");
            argProp.put("description", "The argument to pass to this tool");
        }

        body.put("tool_choice", "auto");

        return objectMapper.writeValueAsString(body);
    }

    private void addMessage(ArrayNode messages, String role, String content) {
        ObjectNode msg = messages.addObject();
        msg.put("role", role);
        msg.put("content", content);
    }

    private AgentAction parseResponse(String responseBody) throws Exception {
        JsonNode root = objectMapper.readTree(responseBody);
        JsonNode choice = root.path("choices").path(0);
        JsonNode message = choice.path("message");
        String finishReason = choice.path("finish_reason").asText("stop");

        if ("tool_calls".equals(finishReason) && message.has("tool_calls")) {
            JsonNode toolCall = message.path("tool_calls").path(0);
            String toolName = toolCall.path("function").path("name").asText();
            String argsJson = toolCall.path("function").path("arguments").asText();
            String argument = extractArgument(argsJson);
            LOG.debug("LLM wants to call tool={} argument={}", toolName, argument);
            return AgentAction.callTool(toolName, argument);
        }

        String content = message.path("content").asText();
        if (content.isBlank()) {
            content = "Drive detection complete.";
        }
        return AgentAction.finalAnswer(content);
    }

    /**
     * Extracts the argument value from the tool call JSON.
     * Handles: {"argument": "value"} or {"path": "value"} or {"device": "value"} etc.
     */
    private String extractArgument(String argsJson) {
        try {
            JsonNode args = objectMapper.readTree(argsJson);
            if (args.has("argument")) {
                return args.path("argument").asText();
            }
            // Fall back to first field value if LLM used a different key
            if (args.isObject() && args.fields().hasNext()) {
                return args.fields().next().getValue().asText();
            }
        } catch (Exception e) {
            LOG.warn("Could not parse tool arguments: {}", argsJson);
        }
        return argsJson;
    }
}
