package com.pfplaybackend.api.virtualdj;

import com.pfplaybackend.api.virtualdj.adapter.out.llm.OpenAiChatProvider;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.http.HttpMethod.POST;
import static org.springframework.test.web.client.ExpectedCount.never;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.anything;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;

/**
 * {@link OpenAiChatProvider} 단위 테스트. {@link AnthropicChatProviderTest} 와 동일하게 신규 의존성 없이
 * Spring {@link MockRestServiceServer} 를 provider 전용 RestTemplate 에 바인딩해 OpenAI
 * Chat Completions({@code POST /v1/chat/completions}) 호출 형태를 검증한다.
 */
@DisplayName("OpenAiChatProvider — Chat Completions best-effort 호출")
class OpenAiChatProviderTest {

    private static final String BASE_URI = "http://openai.test";

    private OpenAiChatProvider newProvider(String apiKey) {
        return new OpenAiChatProvider(apiKey, BASE_URI, "gpt-4o-mini", 12000L);
    }

    private MockRestServiceServer bindServer(OpenAiChatProvider provider) {
        RestTemplate rt = (RestTemplate) ReflectionTestUtils.getField(provider, "restTemplate");
        return MockRestServiceServer.createServer(rt);
    }

    @Test
    @DisplayName("200 + message content → 텍스트를 반환하고 요청 형태(Bearer/messages)를 검증한다")
    void returnsTextAndSendsCorrectRequest() {
        OpenAiChatProvider provider = newProvider("sk-test");
        MockRestServiceServer server = bindServer(provider);

        server.expect(requestTo(BASE_URI + "/v1/chat/completions"))
                .andExpect(method(POST))
                .andExpect(header("Authorization", "Bearer sk-test"))
                .andExpect(jsonPath("$.model").value("gpt-4o-mini"))
                .andExpect(jsonPath("$.max_tokens").value(64))
                .andExpect(jsonPath("$.messages[0].role").value("system"))
                .andExpect(jsonPath("$.messages[0].content").value("system-prompt"))
                .andExpect(jsonPath("$.messages[1].role").value("user"))
                .andExpect(jsonPath("$.messages[1].content").value("user-content"))
                .andRespond(withStatus(HttpStatus.OK)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body("{\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":\"안녕!\"}}]}"));

        String result = provider.complete("system-prompt", "user-content", 64);

        assertThat(result).isEqualTo("안녕!");
        server.verify();
    }

    @Test
    @DisplayName("content 가 null/누락 → 예외 없이 빈 문자열을 반환한다")
    void returnsEmptyWhenContentMissing() {
        OpenAiChatProvider provider = newProvider("sk-test");
        MockRestServiceServer server = bindServer(provider);

        server.expect(requestTo(BASE_URI + "/v1/chat/completions"))
                .andRespond(withStatus(HttpStatus.OK)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body("{\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":null}}]}"));

        assertThat(provider.complete("s", "u", 64)).isEqualTo("");
        server.verify();
    }

    @Test
    @DisplayName("500 응답 → 예외 없이 빈 문자열을 반환한다")
    void returnsEmptyOnServerError() {
        OpenAiChatProvider provider = newProvider("sk-test");
        MockRestServiceServer server = bindServer(provider);

        server.expect(requestTo(BASE_URI + "/v1/chat/completions"))
                .andRespond(withServerError());

        assertThat(provider.complete("s", "u", 64)).isEqualTo("");
        server.verify();
    }

    @Test
    @DisplayName("api-key 가 blank → 호출 없이 빈 문자열을 반환한다")
    void returnsEmptyWithoutCallWhenKeyBlank() {
        OpenAiChatProvider provider = newProvider("");
        MockRestServiceServer server = bindServer(provider);

        // 어떤 요청도 발생하지 않아야 한다.
        server.expect(never(), anything());

        assertThat(provider.complete("s", "u", 64)).isEqualTo("");
        server.verify();
    }
}
