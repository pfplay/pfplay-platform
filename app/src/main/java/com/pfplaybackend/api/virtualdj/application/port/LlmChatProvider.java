package com.pfplaybackend.api.virtualdj.application.port;

/**
 * 봇 채팅 응답 텍스트를 생성하는 LLM 프로바이더 포트.
 *
 * <p><b>best-effort 계약:</b> 호출자에게 절대 예외를 던지지 않는다. API 키 미설정, HTTP 오류,
 * 타임아웃, 파싱 실패 등 어떤 실패에서도 빈 문자열({@code ""})을 반환한다. 빈 문자열을 받은 호출자는
 * 그 회차의 봇 응답을 조용히 건너뛴다(채팅을 송신하지 않는다).
 *
 * <p>채팅 내용은 신뢰할 수 없는 입력(prompt injection)이므로, 프롬프트 조립 책임은 호출자
 * ({@code ChatPromptAssembler})에 있고 이 포트는 system/user 텍스트를 그대로 전달만 한다.
 */
public interface LlmChatProvider {

    /**
     * @param systemPrompt 고정 규칙 + 페르소나 + 방 컨텍스트가 합쳐진 system 프롬프트
     * @param userContent  최근 대화가 단일 user 메시지로 합쳐진 본문
     * @param maxTokens    응답 최대 토큰 수
     * @return 생성된 응답 텍스트, 실패 시 빈 문자열
     */
    String complete(String systemPrompt, String userContent, int maxTokens);
}
