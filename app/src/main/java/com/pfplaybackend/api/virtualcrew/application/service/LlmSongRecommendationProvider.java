package com.pfplaybackend.api.virtualcrew.application.service;

import com.pfplaybackend.api.virtualcrew.application.port.LlmChatProvider;
import com.pfplaybackend.api.virtualcrew.application.port.RoomContextReader.RoomContext;
import com.pfplaybackend.api.virtualcrew.application.port.SongRecommendationProvider;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.Arrays;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * LLM 을 이용해 방 컨셉 + 고반응 우승 곡 기반으로 신규 곡명을 추천한다.
 *
 * <p>best-effort: LLM 호출 실패·파싱 실패 모두 빈 리스트 반환(예외 없음).
 */
@Service
@RequiredArgsConstructor
public class LlmSongRecommendationProvider implements SongRecommendationProvider {

    /** 추천 프롬프트용 최대 응답 토큰 수. */
    static final int RECOMMEND_MAX_TOKENS = 256;

    /** 줄 맨 앞 번호/불릿 접두사 제거 패턴 (예: "1. ", "2) ", "- ", "* "). */
    private static final Pattern PREFIX_PATTERN = Pattern.compile("^\\s*(\\d+[.)]|[-*])\\s*");

    private final LlmChatProvider llm;

    @Override
    public List<String> recommend(RoomContext roomContext, List<String> winnerTitles, int count) {
        if (count <= 0) {
            return List.of();
        }
        try {
            String systemPrompt = buildSystemPrompt(roomContext);
            String userContent = buildUserContent(winnerTitles);
            String raw = llm.complete(systemPrompt, userContent, RECOMMEND_MAX_TOKENS);
            if (raw == null || raw.isBlank()) {
                return List.of();
            }
            return parseLines(raw, count);
        } catch (Exception e) {
            return List.of();
        }
    }

    // ─── private helpers ──────────────────────────────────────────────────────

    private String buildSystemPrompt(RoomContext ctx) {
        StringBuilder sb = new StringBuilder();
        sb.append("당신은 음악 큐레이터입니다. 방의 컨셉과 인기 곡을 참고하여 잘 어울리는 신규 곡을 추천해야 합니다.\n");
        sb.append("응답 규칙:\n");
        sb.append("- 한 줄에 곡 이름 하나만 출력하세요.\n");
        sb.append("- 번호, 불릿, 설명, 부가 텍스트 없이 곡 이름(아티스트 - 곡명 형식 권장)만 출력하세요.\n");
        sb.append("- 빈 줄 없이 연속해서 출력하세요.\n\n");
        sb.append("방 정보:\n");
        sb.append("제목: ").append(ctx.title()).append("\n");
        if (ctx.introduction() != null && !ctx.introduction().isBlank()) {
            sb.append("소개: ").append(ctx.introduction()).append("\n");
        }
        if (ctx.nowPlayingTitle() != null && !ctx.nowPlayingTitle().isBlank()) {
            sb.append("현재 재생 중: ").append(ctx.nowPlayingTitle()).append("\n");
        }
        return sb.toString();
    }

    private String buildUserContent(List<String> winnerTitles) {
        if (winnerTitles == null || winnerTitles.isEmpty()) {
            return "아직 인기 곡 데이터가 없습니다. 방 컨셉에 어울리는 곡을 자유롭게 추천해주세요.";
        }
        return "다음 인기 곡들과 비슷한 분위기의 새로운 곡을 추천해주세요:\n"
                + String.join("\n", winnerTitles);
    }

    private List<String> parseLines(String raw, int count) {
        return Arrays.stream(raw.split("\n"))
                .map(line -> PREFIX_PATTERN.matcher(line).replaceFirst(""))
                .map(String::trim)
                .filter(line -> !line.isBlank())
                .distinct()
                .limit(count)
                .collect(Collectors.toList());
    }
}
