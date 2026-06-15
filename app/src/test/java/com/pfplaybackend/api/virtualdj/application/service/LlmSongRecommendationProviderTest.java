package com.pfplaybackend.api.virtualdj.application.service;

import com.pfplaybackend.api.virtualdj.application.port.LlmChatProvider;
import com.pfplaybackend.api.virtualdj.application.port.RoomContextReader;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class LlmSongRecommendationProviderTest {

    @Mock
    private LlmChatProvider llm;

    @InjectMocks
    private LlmSongRecommendationProvider provider;

    private RoomContextReader.RoomContext ctx;

    @BeforeEach
    void setUp() {
        ctx = new RoomContextReader.RoomContext("90s 시티팝", "집중용", null);
    }

    @Test
    @DisplayName("LLM 빈 응답 → 빈 리스트 반환")
    void empty_llm_response_returns_empty_list() {
        when(llm.complete(anyString(), anyString(), anyInt())).thenReturn("");
        assertThat(provider.recommend(ctx, List.of("Song A"), 6)).isEmpty();
    }

    @Test
    @DisplayName("번호/불릿/공백 줄 제거 후 순서 보존")
    void parse_strips_numbering_bullets_blank_lines_preserves_order() {
        when(llm.complete(anyString(), anyString(), anyInt()))
                .thenReturn("1. Daft Punk - Around the World\n- Justice - Genesis\n\n  M83 - Midnight City  \n");
        assertThat(provider.recommend(ctx, List.of("Song A"), 6))
                .containsExactly("Daft Punk - Around the World", "Justice - Genesis", "M83 - Midnight City");
    }

    @Test
    @DisplayName("count 로 결과 수 잘림")
    void truncate_to_count() {
        when(llm.complete(anyString(), anyString(), anyInt())).thenReturn("a\nb\nc\nd");
        assertThat(provider.recommend(ctx, List.of(), 2)).containsExactly("a", "b");
    }

    @Test
    @DisplayName("count=0 이면 바로 빈 리스트")
    void count_zero_returns_empty() {
        assertThat(provider.recommend(ctx, List.of("Song A"), 0)).isEmpty();
    }

    @Test
    @DisplayName("nowPlayingTitle non-null 일 때도 정상 동작")
    void with_now_playing_title() {
        RoomContextReader.RoomContext ctxWithNowPlaying =
                new RoomContextReader.RoomContext("Lo-Fi 방", null, "Nujabes - Feather");
        when(llm.complete(anyString(), anyString(), anyInt()))
                .thenReturn("* Bonobo - Kiara\n* Nujabes - Aruarian Dance");
        assertThat(provider.recommend(ctxWithNowPlaying, List.of(), 5))
                .containsExactly("Bonobo - Kiara", "Nujabes - Aruarian Dance");
    }
}
