package com.pfplaybackend.api.virtualdj;

import com.pfplaybackend.api.virtualdj.adapter.out.llm.AnthropicChatProvider;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.ExpectedCount.never;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.anything;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.http.HttpMethod.POST;

/**
 * {@link AnthropicChatProvider} 단위 테스트. HTTP 검증은 신규 의존성(WireMock) 없이 Spring 의
 * {@link MockRestServiceServer} 를 provider 의 전용 RestTemplate 에 바인딩해 수행한다.
 */
@DisplayName("AnthropicChatProvider — Messages API best-effort 호출")
class AnthropicChatProviderTest {

    private static final String BASE_URI = "http://anthropic.test";

    private AnthropicChatProvider newProvider(String apiKey) {
        return new AnthropicChatProvider(apiKey, BASE_URI, "claude-haiku-4-5", 12000L);
    }

    private MockRestServiceServer bindServer(AnthropicChatProvider provider) {
        RestTemplate rt = (RestTemplate) ReflectionTestUtils.getField(provider, "restTemplate");
        return MockRestServiceServer.createServer(rt);
    }

    @Test
    @DisplayName("200 + text 블록 → 텍스트를 반환하고 요청 형태를 검증한다")
    void returnsTextAndSendsCorrectRequest() {
        AnthropicChatProvider provider = newProvider("sk-test");
        MockRestServiceServer server = bindServer(provider);

        server.expect(requestTo(BASE_URI + "/v1/messages"))
                .andExpect(method(POST))
                .andExpect(header("x-api-key", "sk-test"))
                .andExpect(header("anthropic-version", "2023-06-01"))
                .andExpect(jsonPath("$.model").value("claude-haiku-4-5"))
                .andExpect(jsonPath("$.max_tokens").value(64))
                .andExpect(jsonPath("$.system").value("system-prompt"))
                .andExpect(jsonPath("$.messages[0].role").value("user"))
                .andExpect(jsonPath("$.messages[0].content").value("user-content"))
                .andRespond(withStatus(HttpStatus.OK)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body("{\"content\":[{\"type\":\"text\",\"text\":\"안녕!\"}]}"));

        String result = provider.complete("system-prompt", "user-content", 64);

        assertThat(result).isEqualTo("안녕!");
        server.verify();
    }

    @Test
    @DisplayName("첫 text 블록을 선택한다 (text 가 아닌 블록은 건너뛴다)")
    void picksFirstTextBlock() {
        AnthropicChatProvider provider = newProvider("sk-test");
        MockRestServiceServer server = bindServer(provider);

        server.expect(requestTo(BASE_URI + "/v1/messages"))
                .andRespond(withStatus(HttpStatus.OK)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body("{\"content\":[{\"type\":\"thinking\",\"text\":\"x\"},"
                                + "{\"type\":\"text\",\"text\":\"진짜답변\"}]}"));

        assertThat(provider.complete("s", "u", 64)).isEqualTo("진짜답변");
        server.verify();
    }

    @Test
    @DisplayName("500 응답 → 예외 없이 빈 문자열을 반환한다")
    void returnsEmptyOnServerError() {
        AnthropicChatProvider provider = newProvider("sk-test");
        MockRestServiceServer server = bindServer(provider);

        server.expect(requestTo(BASE_URI + "/v1/messages"))
                .andRespond(withServerError());

        assertThat(provider.complete("s", "u", 64)).isEqualTo("");
        server.verify();
    }

    @Test
    @DisplayName("api-key 가 blank → 호출 없이 빈 문자열을 반환한다")
    void returnsEmptyWithoutCallWhenKeyBlank() {
        AnthropicChatProvider provider = newProvider("");
        MockRestServiceServer server = bindServer(provider);

        // 어떤 요청도 발생하지 않아야 한다.
        server.expect(never(), anything());

        assertThat(provider.complete("s", "u", 64)).isEqualTo("");
        server.verify();
    }
}
