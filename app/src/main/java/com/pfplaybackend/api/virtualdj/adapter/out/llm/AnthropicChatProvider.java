package com.pfplaybackend.api.virtualdj.adapter.out.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.pfplaybackend.api.virtualdj.application.port.LlmChatProvider;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.net.URI;
import java.net.http.HttpClient;
import java.time.Duration;

/**
 * Anthropic Messages API({@code POST /v1/messages})를 호출하는 {@link LlmChatProvider} 구현.
 *
 * <h2>best-effort 계약</h2>
 * {@link #complete} 는 절대 예외를 던지지 않는다. API 키 미설정 → 즉시 {@code ""}(호출 자체를 하지 않음).
 * HTTP 4xx/5xx, 타임아웃, 파싱 실패 → catch 후 {@code log.warn} + {@code ""}. 성공 시 첫 text 블록 텍스트
 * (없으면 {@code ""}).
 *
 * <h2>prompt caching</h2>
 * system 을 {@code [{type:text, text:…, cache_control:{type:ephemeral}}]} 블록 배열로 보내 고정 규칙 +
 * 페르소나 + 방 컨텍스트 prefix 의 prompt caching(GA, beta 헤더 불필요)을 활성화한다.
 */
@Slf4j
@Component
public class AnthropicChatProvider implements LlmChatProvider {

    private static final String ANTHROPIC_VERSION = "2023-06-01";

    private final String apiKey;
    private final String baseUri;
    private final String model;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final RestTemplate restTemplate;

    public AnthropicChatProvider(
            @Value("${service-api.anthropic.api-key:}") String apiKey,
            @Value("${service-api.anthropic.base-uri:https://api.anthropic.com}") String baseUri,
            @Value("${service-api.anthropic.model:claude-haiku-4-5-20251001}") String model,
            @Value("${service-api.anthropic.timeout-ms:12000}") long timeoutMs) {
        this.apiKey = apiKey;
        this.baseUri = baseUri;
        this.model = model;
        this.restTemplate = buildRestTemplate(timeoutMs);
    }

    /**
     * connect/read 타임아웃을 동일하게 적용한 전용 RestTemplate.
     * Java 11+ HttpClient 기반 JdkClientHttpRequestFactory 를 다른 호출부와 동일 스타일로 사용한다.
     */
    private static RestTemplate buildRestTemplate(long timeoutMs) {
        HttpClient httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(timeoutMs))
                .build();
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(httpClient);
        factory.setReadTimeout(Duration.ofMillis(timeoutMs));
        return new RestTemplate(factory);
    }

    /** 테스트(MockRestServiceServer)에서 이 인스턴스의 RestTemplate 에 바인딩하기 위한 접근자. */
    RestTemplate getRestTemplate() {
        return restTemplate;
    }

    @Override
    public String complete(String systemPrompt, String userContent, int maxTokens) {
        if (apiKey == null || apiKey.isBlank()) {
            log.debug("no anthropic key, skipping bot chat completion");
            return "";
        }

        try {
            URI uri = URI.create(baseUri + "/v1/messages");

            HttpHeaders headers = new HttpHeaders();
            headers.set("x-api-key", apiKey);
            headers.set("anthropic-version", ANTHROPIC_VERSION);
            headers.setContentType(MediaType.APPLICATION_JSON);

            String body = buildRequestBody(systemPrompt, userContent, maxTokens);
            HttpEntity<String> entity = new HttpEntity<>(body, headers);

            ResponseEntity<String> response =
                    restTemplate.exchange(uri, HttpMethod.POST, entity, String.class);

            return extractFirstText(response.getBody());
        } catch (Exception e) {
            log.warn("anthropic chat completion failed: {}", e.toString());
            return "";
        }
    }

    private String buildRequestBody(String systemPrompt, String userContent, int maxTokens)
            throws Exception {
        ObjectNode root = objectMapper.createObjectNode();
        root.put("model", model);
        root.put("max_tokens", maxTokens);

        // system: [{ type:text, text:…, cache_control:{type:ephemeral} }]
        ArrayNode systemArr = root.putArray("system");
        ObjectNode systemBlock = systemArr.addObject();
        systemBlock.put("type", "text");
        systemBlock.put("text", systemPrompt);
        systemBlock.putObject("cache_control").put("type", "ephemeral");

        // messages: [{ role:user, content:… }]
        ArrayNode messagesArr = root.putArray("messages");
        ObjectNode userMsg = messagesArr.addObject();
        userMsg.put("role", "user");
        userMsg.put("content", userContent);

        return objectMapper.writeValueAsString(root);
    }

    /** content 배열에서 첫 {@code type=="text"} 블록의 text 를 반환. 없으면 "". */
    private String extractFirstText(String responseBody) throws Exception {
        if (responseBody == null || responseBody.isBlank()) {
            return "";
        }
        JsonNode root = objectMapper.readTree(responseBody);
        JsonNode content = root.path("content");
        if (content.isArray()) {
            for (JsonNode block : content) {
                if ("text".equals(block.path("type").asText())) {
                    return block.path("text").asText("");
                }
            }
        }
        return "";
    }
}
