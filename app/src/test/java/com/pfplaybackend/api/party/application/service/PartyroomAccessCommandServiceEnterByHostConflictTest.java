package com.pfplaybackend.api.party.application.service;

import com.pfplaybackend.api.common.domain.value.UserId;
import com.pfplaybackend.api.common.exception.http.ConflictException;
import com.pfplaybackend.api.party.application.port.out.PlaybackControlPort;
import com.pfplaybackend.api.party.application.port.out.UserProfileQueryPort;
import com.pfplaybackend.api.party.domain.entity.data.PartyroomData;
import com.pfplaybackend.api.party.domain.port.PartyroomAggregatePort;
import com.pfplaybackend.api.party.domain.service.PartyroomAggregateService;
import com.pfplaybackend.api.party.domain.value.PartyroomId;
import com.pfplaybackend.api.user.application.dto.shared.ProfileSettingDto;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.PlatformTransactionManager;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * #351 enterByHost 의 uk_crew_active_user 위반 매핑 회귀 잠금.
 *
 * <p>auto-exit statement 와 HOST crew INSERT 사이의 밀리초 창에 같은 유저의 타 기기 tryEnter 가
 * active_user_id 슬롯을 선점하면 INSERT 가 유니크 위반을 맞는다. 이때 미처리 500 대신
 * CRW-005 CONFLICT 로 매핑되어야 하고(재시도 유도), 그 외 무결성 위반은 원예외를 유지해야 한다.
 */
@ExtendWith(MockitoExtension.class)
class PartyroomAccessCommandServiceEnterByHostConflictTest {

    @Mock private ApplicationEventPublisher eventPublisher;
    @Mock private PartyroomAggregatePort aggregatePort;
    @Mock private PartyroomAggregateService partyroomAggregateService;
    @Mock private PartyroomQueryService partyroomQueryService;
    @Mock private PlaybackControlPort playbackControlPort;
    @Mock private UserProfileQueryPort userProfileQueryPort;
    @Mock private Clock clock;
    @Mock private PlatformTransactionManager transactionManager;

    @InjectMocks
    private PartyroomAccessCommandService service;

    private final UserId hostId = new UserId(1001L);
    private PartyroomData partyroom;

    @BeforeEach
    void setUp() {
        lenient().when(clock.instant()).thenReturn(Instant.parse("2025-01-01T00:00:00Z"));
        lenient().when(clock.getZone()).thenReturn(ZoneId.of("Asia/Seoul"));
        // 프로필 존재(assertHasProfile 통과) + 기존 활성 방 없음(auto-exit 미발동)
        lenient().when(userProfileQueryPort.getUsersProfileSetting(List.of(hostId)))
                .thenReturn(Map.of(hostId, mock(ProfileSettingDto.class)));
        lenient().when(partyroomQueryService.getMyActivePartyroom(hostId)).thenReturn(Optional.empty());

        partyroom = mock(PartyroomData.class);
        lenient().when(partyroom.getPartyroomId()).thenReturn(new PartyroomId(77L));
    }

    @Test
    @DisplayName("HOST crew INSERT 가 uk_crew_active_user 위반 → CRW-005 CONFLICT 매핑")
    void active_user_constraint_maps_to_conflict() {
        when(aggregatePort.saveCrew(any())).thenThrow(new DataIntegrityViolationException(
                "could not execute statement; Duplicate entry '1001' for key 'crew.uk_crew_active_user'"));

        assertThatThrownBy(() -> service.enterByHost(hostId, partyroom))
                .isInstanceOf(ConflictException.class)
                .hasFieldOrPropertyWithValue("errorCode", "CRW-005");
    }

    @Test
    @DisplayName("그 외 무결성 위반(uk_crew_partyroom_user 등) → 원예외 그대로 전파")
    void other_constraint_rethrows_original() {
        DataIntegrityViolationException original = new DataIntegrityViolationException(
                "could not execute statement; Duplicate entry '77-1001' for key 'crew.uk_crew_partyroom_user'");
        when(aggregatePort.saveCrew(any())).thenThrow(original);

        assertThatThrownBy(() -> service.enterByHost(hostId, partyroom))
                .isSameAs(original);
    }
}
