package com.pfplaybackend.api.virtualdj.adapter.out.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.pfplaybackend.api.virtualdj.application.port.LlmChatProvider;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
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
 * <h2>system 프롬프트 / prompt caching</h2>
 * system 은 평문 문자열로 보낸다. 채팅용 system(고정 규칙 + 페르소나 + 방 컨텍스트)은 짧아(수백 토큰)
 * Haiku 4.5 의 최소 캐시 prefix(4096 토큰) 미만이라 prompt caching 이 어차피 걸리지 않는다
 * (cache_control 을 붙여도 silent no-op). 따라서 불필요한 블록 배열/cache_control 을 두지 않는다.
 * 향후 system 이 4096 토큰을 넘기게 설계되면 그때 {@code [{type:text,…,cache_control:{type:ephemeral}}]}
 * 배열 형태 + cache_control 로 캐싱을 도입한다.
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "service-api.llm.provider", havingValue = "anthropic")
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
            @Value("${service-api.anthropic.model:claude-haiku-4-5}") String model,
            @Value("${service-api.anthropic.timeout-ms:12000}") long timeoutMs) {
        this.apiKey = apiKey;
        this.baseUri = baseUri;
        this.model = model;
        this.restTemplate = buildRestTemplate(timeoutMs);

        // 키 부재는 정상 상태다(프로세스 다운 아님) — 가시성을 위해 기동 시 한 번 알린다.
        if (apiKey == null || apiKey.isBlank()) {
            log.info("ANTHROPIC_API_KEY 미설정 — 봇 채팅 LLM 응답은 no-op(빈 응답→드롭). 프로세스 정상 기동. "
                    + "키 설정 후 자동 동작(전역 토글 vdj.chat.enabled 과는 별개 레이어).");
        } else {
            log.info("AnthropicChatProvider 준비 완료 — model={}", model);
        }
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

        // system: 평문 문자열(짧은 채팅 프롬프트 → 캐싱 임계 미만, 블록배열/cache_control 불필요)
        root.put("system", systemPrompt);

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
