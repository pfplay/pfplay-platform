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
 * OpenAI Chat Completions API({@code POST /v1/chat/completions})를 호출하는 {@link LlmChatProvider} 구현.
 *
 * <h2>프로바이더 선택</h2>
 * {@code service-api.llm.provider} 가 {@code openai}(미설정 시 기본)일 때만 빈으로 등록된다. {@code anthropic}
 * 이면 대신 {@link AnthropicChatProvider} 가 등록된다 — 두 어댑터는 같은 포트의 상호배타 구현이다.
 *
 * <h2>best-effort 계약</h2>
 * {@link #complete} 는 절대 예외를 던지지 않는다. API 키 미설정 → 즉시 {@code ""}(호출 자체를 하지 않음).
 * HTTP 4xx/5xx, 타임아웃, 파싱 실패 → catch 후 {@code log.warn} + {@code ""}. 성공 시 첫 choice 의
 * {@code message.content}(문자열이 아니면 {@code ""}).
 *
 * <h2>system/user 매핑</h2>
 * Anthropic 의 별도 {@code system} 필드와 달리 OpenAI 는 {@code messages} 배열의 {@code role:"system"}
 * 메시지로 system 프롬프트를 전달한다. 따라서 systemPrompt→messages[0](system), userContent→messages[1](user).
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "service-api.llm.provider", havingValue = "openai", matchIfMissing = true)
public class OpenAiChatProvider implements LlmChatProvider {

    private final String apiKey;
    private final String baseUri;
    private final String model;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final RestTemplate restTemplate;

    public OpenAiChatProvider(
            @Value("${service-api.openai.api-key:}") String apiKey,
            @Value("${service-api.openai.base-uri:https://api.openai.com}") String baseUri,
            @Value("${service-api.openai.model:gpt-4o-mini}") String model,
            @Value("${service-api.openai.timeout-ms:12000}") long timeoutMs) {
        this.apiKey = apiKey;
        this.baseUri = baseUri;
        this.model = model;
        this.restTemplate = buildRestTemplate(timeoutMs);

        // 키 부재는 정상 상태다(프로세스 다운 아님) — 가시성을 위해 기동 시 한 번 알린다.
        if (apiKey == null || apiKey.isBlank()) {
            log.info("OPENAI_API_KEY 미설정 — 봇 채팅/선곡 LLM 응답은 no-op(빈 응답→드롭). 프로세스 정상 기동. "
                    + "키 설정 후 자동 동작(전역 토글 vdj.chat.enabled / vdj.playlist.self_update.enabled 과는 별개 레이어).");
        } else {
            log.info("OpenAiChatProvider 준비 완료 — model={}", model);
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
            log.debug("no openai key, skipping completion");
            return "";
        }

        try {
            URI uri = URI.create(baseUri + "/v1/chat/completions");

            HttpHeaders headers = new HttpHeaders();
            headers.setBearerAuth(apiKey);
            headers.setContentType(MediaType.APPLICATION_JSON);

            String body = buildRequestBody(systemPrompt, userContent, maxTokens);
            HttpEntity<String> entity = new HttpEntity<>(body, headers);

            ResponseEntity<String> response =
                    restTemplate.exchange(uri, HttpMethod.POST, entity, String.class);

            return extractFirstContent(response.getBody());
        } catch (Exception e) {
            log.warn("openai chat completion failed: {}", e.toString());
            return "";
        }
    }

    private String buildRequestBody(String systemPrompt, String userContent, int maxTokens)
            throws Exception {
        ObjectNode root = objectMapper.createObjectNode();
        root.put("model", model);
        root.put("max_tokens", maxTokens);

        // messages: [{ role:system, content:… }, { role:user, content:… }]
        ArrayNode messagesArr = root.putArray("messages");
        ObjectNode systemMsg = messagesArr.addObject();
        systemMsg.put("role", "system");
        systemMsg.put("content", systemPrompt);
        ObjectNode userMsg = messagesArr.addObject();
        userMsg.put("role", "user");
        userMsg.put("content", userContent);

        return objectMapper.writeValueAsString(root);
    }

    /** {@code choices[0].message.content} 가 문자열이면 그 텍스트, 아니면(null/누락/빈 choices) "". */
    private String extractFirstContent(String responseBody) throws Exception {
        if (responseBody == null || responseBody.isBlank()) {
            return "";
        }
        JsonNode root = objectMapper.readTree(responseBody);
        JsonNode choices = root.path("choices");
        if (choices.isArray() && !choices.isEmpty()) {
            JsonNode content = choices.get(0).path("message").path("content");
            if (content.isTextual()) {
                return content.asText();
            }
        }
        return "";
    }
}
