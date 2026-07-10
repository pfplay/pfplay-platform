package com.pfplaybackend.api.virtualcrew;

import com.pfplaybackend.api.party.application.service.PartyroomQueryService;
import com.pfplaybackend.api.party.domain.entity.data.PartyroomData;
import com.pfplaybackend.api.party.domain.value.PartyroomId;
import com.pfplaybackend.api.virtualcrew.application.port.RoomContextReader;
import com.pfplaybackend.api.virtualcrew.application.service.RoomContextReaderImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

/**
 * {@link RoomContextReaderImpl} 단위 테스트 — 방 제목/소개 + 현재 곡 제목을 조립한다.
 * party query service 는 mock 으로 격리한다.
 */
@ExtendWith(MockitoExtension.class)
class RoomContextReaderTest {

    @Mock
    PartyroomQueryService partyroomQueryService;

    RoomContextReader reader;

    private final PartyroomId room = new PartyroomId(1L);

    @BeforeEach
    void setUp() {
        reader = new RoomContextReaderImpl(partyroomQueryService);
    }

    private PartyroomData partyroom(String title, String introduction) {
        return PartyroomData.builder()
                .title(title)
                .introduction(introduction)
                .build();
    }

    @Test
    @DisplayName("재생 활성 — title/introduction/nowPlayingTitle 모두 채워진다")
    void activated_playback_fills_now_playing() {
        when(partyroomQueryService.getPartyroomById(room))
                .thenReturn(partyroom("Friday Night", "Welcome to the party"));
        when(partyroomQueryService.getCurrentPlaybackName(room))
                .thenReturn("Daft Punk - Around the World");

        RoomContextReader.RoomContext ctx = reader.read(room);

        assertThat(ctx.title()).isEqualTo("Friday Night");
        assertThat(ctx.introduction()).isEqualTo("Welcome to the party");
        assertThat(ctx.nowPlayingTitle()).isEqualTo("Daft Punk - Around the World");
    }

    @Test
    @DisplayName("재생 비활성 — nowPlayingTitle null, title/introduction 은 채워진다")
    void not_activated_now_playing_null() {
        when(partyroomQueryService.getPartyroomById(room))
                .thenReturn(partyroom("Chill Room", "Lo-fi vibes"));
        when(partyroomQueryService.getCurrentPlaybackName(room))
                .thenReturn(null);

        RoomContextReader.RoomContext ctx = reader.read(room);

        assertThat(ctx.title()).isEqualTo("Chill Room");
        assertThat(ctx.introduction()).isEqualTo("Lo-fi vibes");
        assertThat(ctx.nowPlayingTitle()).isNull();
    }

    @Test
    @DisplayName("소개가 null 이어도 그대로 전달한다 (title/nowPlaying 영향 없음)")
    void null_introduction_passed_through() {
        when(partyroomQueryService.getPartyroomById(room))
                .thenReturn(partyroom("No Intro Room", null));
        when(partyroomQueryService.getCurrentPlaybackName(room))
                .thenReturn("Some Song");

        RoomContextReader.RoomContext ctx = reader.read(room);

        assertThat(ctx.title()).isEqualTo("No Intro Room");
        assertThat(ctx.introduction()).isNull();
        assertThat(ctx.nowPlayingTitle()).isEqualTo("Some Song");
    }
}
