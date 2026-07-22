package com.pfplaybackend.api.administration.application.service;

import com.pfplaybackend.api.administration.adapter.in.web.payload.response.AdminPartyroomDetailResponse;
import com.pfplaybackend.api.administration.adapter.in.web.payload.response.AdminPartyroomListItemResponse;
import com.pfplaybackend.api.administration.adapter.in.web.payload.response.AdminPartyroomListItemResponse.VirtualCrewSummary;
import com.pfplaybackend.api.administration.adapter.out.persistence.AdminPartyroomQueryRepository;
import com.pfplaybackend.api.administration.adapter.out.persistence.PartyroomAdminActionRepository;
import com.pfplaybackend.api.administration.application.dto.AdminPartyroomListFilter;
import com.pfplaybackend.api.administration.application.dto.AdminPartyroomListRow;
import com.pfplaybackend.api.common.domain.value.UserId;
import com.pfplaybackend.api.common.exception.http.NotFoundException;
import com.pfplaybackend.api.party.adapter.out.persistence.CrewPenaltyHistoryRepository;
import com.pfplaybackend.api.party.domain.entity.data.CrewData;
import com.pfplaybackend.api.party.domain.entity.data.DjData;
import com.pfplaybackend.api.party.domain.entity.data.PartyroomData;
import com.pfplaybackend.api.party.domain.entity.data.PartyroomPlaybackData;
import com.pfplaybackend.api.party.domain.entity.data.history.CrewPenaltyHistoryData;
import com.pfplaybackend.api.party.domain.enums.DisplayFlag;
import com.pfplaybackend.api.party.domain.enums.GradeType;
import com.pfplaybackend.api.party.domain.enums.PartyroomStatus;
import com.pfplaybackend.api.party.domain.enums.PenaltyType;
import com.pfplaybackend.api.party.domain.enums.PunisherType;
import com.pfplaybackend.api.party.domain.enums.StageType;
import com.pfplaybackend.api.virtualcrew.domain.enums.VirtualCrewStatus;
import com.pfplaybackend.api.party.domain.port.PartyroomAggregatePort;
import com.pfplaybackend.api.party.domain.value.CrewId;
import com.pfplaybackend.api.party.domain.value.LinkDomain;
import com.pfplaybackend.api.party.domain.value.PartyroomId;
import com.pfplaybackend.api.party.domain.value.PlaybackTimeLimit;
import com.pfplaybackend.api.user.adapter.out.persistence.MemberRepository;
import com.pfplaybackend.api.user.adapter.out.persistence.UserAccountRepository;
import com.pfplaybackend.api.user.domain.entity.data.MemberData;
import com.pfplaybackend.api.user.domain.entity.data.ProfileData;
import com.pfplaybackend.api.user.domain.entity.data.UserAccountData;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;

import java.lang.reflect.Field;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AdminPartyroomQueryServiceTest {

    @Mock private AdminPartyroomQueryRepository adminPartyroomQueryRepository;
    @Mock private PartyroomAggregatePort aggregatePort;
    @Mock private UserAccountRepository userAccountRepository;
    @Mock private MemberRepository memberRepository;
    @Mock private PartyroomAdminActionRepository adminActionRepository;
    @Mock private CrewPenaltyHistoryRepository crewPenaltyHistoryRepository;

    @InjectMocks
    private AdminPartyroomQueryService service;

    private static final PartyroomId PID = new PartyroomId(100L);
    private static final UserId HOST_UID = new UserId(7L);

    private PartyroomData partyroom;

    @BeforeEach
    void setUp() {
        partyroom = PartyroomData.create(
                "RoomTitle", "intro", LinkDomain.of("link"),
                PlaybackTimeLimit.ofMinutes(5),
                StageType.GENERAL, HOST_UID
        );
        setField(partyroom, "id", 100L);
        setField(partyroom, "lastActivityAt", LocalDateTime.of(2026, 4, 27, 10, 0));
    }

    @Test
    @DisplayName("list - delegates to repository and maps Row → ListItemResponse")
    void list_passthrough() {
        AdminPartyroomListRow row = new AdminPartyroomListRow(
                100L, "RoomTitle", StageType.GENERAL,
                7L, "hostNick",
                4, 2L,
                Boolean.TRUE,
                PartyroomStatus.ACTIVE, DisplayFlag.NORMAL,
                LocalDateTime.of(2026, 4, 1, 0, 0),
                LocalDateTime.of(2026, 4, 27, 10, 0),
                new VirtualCrewSummary(VirtualCrewStatus.MANAGED, 3, 2L)
        );
        Pageable pageable = PageRequest.of(0, 20);
        AdminPartyroomListFilter filter = new AdminPartyroomListFilter(null, null, null, null, null);
        when(adminPartyroomQueryRepository.findAdminList(eq(filter), eq(pageable)))
                .thenReturn(new PageImpl<>(List.of(row), pageable, 1L));

        Page<AdminPartyroomListItemResponse> page = service.list(filter, pageable);

        assertThat(page.getTotalElements()).isEqualTo(1L);
        AdminPartyroomListItemResponse item = page.getContent().get(0);
        assertThat(item.partyroomId()).isEqualTo(100L);
        assertThat(item.title()).isEqualTo("RoomTitle");
        assertThat(item.hostNickname()).isEqualTo("hostNick");
        assertThat(item.crewCount()).isEqualTo(4);
        assertThat(item.djCount()).isEqualTo(2L);
        assertThat(item.playbackActivated()).isTrue();
        assertThat(item.status()).isEqualTo(PartyroomStatus.ACTIVE);
        // P2 Task 2.2 — virtualCrew 요약은 Row → Response 로 그대로 전달된다.
        assertThat(item.virtualCrew()).isNotNull();
        assertThat(item.virtualCrew().status()).isEqualTo(VirtualCrewStatus.MANAGED);
        assertThat(item.virtualCrew().targetCount()).isEqualTo(3);
        assertThat(item.virtualCrew().botDjCount()).isEqualTo(2L);
    }

    @Test
    @DisplayName("list - null playbackActivated (no PARTYROOM_PLAYBACK row) → false at boundary")
    void list_null_playback_to_false() {
        AdminPartyroomListRow row = new AdminPartyroomListRow(
                101L, "T", StageType.GENERAL, 8L, null,
                0, 0L, null,
                PartyroomStatus.ACTIVE, DisplayFlag.NORMAL,
                LocalDateTime.now(), null,
                null  // config 없는 룸
        );
        Pageable pageable = PageRequest.of(0, 20);
        AdminPartyroomListFilter filter = new AdminPartyroomListFilter(null, null, null, null, null);
        when(adminPartyroomQueryRepository.findAdminList(any(), any()))
                .thenReturn(new PageImpl<>(List.of(row), pageable, 1L));

        AdminPartyroomListItemResponse item = service.list(filter, pageable).getContent().get(0);

        assertThat(item.playbackActivated()).isFalse();
        assertThat(item.hostNickname()).isNull();
        assertThat(item.virtualCrew()).isNull();  // config 없는 룸 → null passthrough
    }

    @Test
    @DisplayName("detail - happy path: composes partyroom + host + crews + djs + admin actions + playback")
    void detail_happy() {
        UserAccountData hostAccount = UserAccountData.createForLocal(HOST_UID, "host@example.com", "hash");
        MemberData hostMember = memberWithNickname(7L, "hostNick");

        // crews
        UserId crewMemberUid = new UserId(11L);
        CrewData crew = CrewData.create(PID, crewMemberUid, GradeType.MODERATOR, null,
                LocalDateTime.of(2026, 4, 27, 9, 0));
        setField(crew, "id", 5001L);
        MemberData crewMember = memberWithNickname(11L, "memberA");
        setField(crewMember, "memberId", 9001L);

        // djs
        DjData dj = DjData.builder()
                .id(8001L)
                .partyroomId(PID)
                .crewId(new CrewId(5001L))
                .orderNumber(0)
                .build();

        // playback
        PartyroomPlaybackData playback = PartyroomPlaybackData.createFor(PID);
        // not activated by default — leaves currentDjCrewId null

        when(aggregatePort.findPartyroomById(PID.getId())).thenReturn(Optional.of(partyroom));
        when(userAccountRepository.findById(HOST_UID)).thenReturn(Optional.of(hostAccount));
        when(memberRepository.findByUserAccountId(HOST_UID.getUid())).thenReturn(Optional.of(hostMember));
        when(aggregatePort.findActiveCrews(PID)).thenReturn(List.of(crew));
        when(memberRepository.findAllByUserAccountIdIn(anyList())).thenReturn(List.of(crewMember));
        when(aggregatePort.findDjsOrdered(PID)).thenReturn(List.of(dj));
        when(adminActionRepository.findTop10ByPartyroomIdOrderByOccurredAtDesc(PID.getId()))
                .thenReturn(List.of());
        when(aggregatePort.findPlaybackState(PID)).thenReturn(playback);
        when(crewPenaltyHistoryRepository.findTop5ByPartyroomIdOrderByPenaltyDateDesc(PID))
                .thenReturn(List.of());

        AdminPartyroomDetailResponse detail = service.detail(PID);

        assertThat(detail.partyroomId()).isEqualTo(100L);
        assertThat(detail.title()).isEqualTo("RoomTitle");
        assertThat(detail.status()).isEqualTo(PartyroomStatus.ACTIVE);
        assertThat(detail.hostUserAccountId()).isEqualTo(7L);
        assertThat(detail.hostEmail()).isEqualTo("host@example.com");
        assertThat(detail.hostNickname()).isEqualTo("hostNick");
        // #358 상세 크루 수 = 라이브 목록(findActiveCrews)과 동일 소스 — 드리프트 가능한
        // crew_count 컬럼(reflection 으로 4 세팅됨)이 아니라 activeCrews.size()=1 이어야 한다.
        assertThat(detail.crewCount()).isEqualTo(1);
        // PR 14g §5: root-level introduction + playbackTimeLimit (minutes) 노출
        assertThat(detail.introduction()).isEqualTo("intro");
        assertThat(detail.playbackTimeLimit()).isEqualTo(5);

        // crews
        assertThat(detail.crews()).hasSize(1);
        AdminPartyroomDetailResponse.CrewSummary crewSummary = detail.crews().get(0);
        assertThat(crewSummary.crewId()).isEqualTo(5001L);
        assertThat(crewSummary.memberId()).isEqualTo(9001L);
        assertThat(crewSummary.gradeType()).isEqualTo(GradeType.MODERATOR);
        assertThat(crewSummary.nickname()).isEqualTo("memberA");

        // djs
        assertThat(detail.djQueue()).hasSize(1);
        AdminPartyroomDetailResponse.DjSummary djSummary = detail.djQueue().get(0);
        assertThat(djSummary.djId()).isEqualTo(8001L);
        assertThat(djSummary.crewId()).isEqualTo(5001L);
        assertThat(djSummary.playlistName()).isNull(); // PR 8 MVP — Playlist port out of scope

        // playback
        assertThat(detail.playback().activated()).isFalse();
        assertThat(detail.playback().currentTrackName()).isNull(); // PR 8 MVP
        assertThat(detail.playback().currentDjCrewId()).isNull();

        // PR 9 — recentPenalties populated from V8; this happy path stubs no rows.
        assertThat(detail.recentPenalties()).isEmpty();
        // PR 13 — reports still empty.
        assertThat(detail.recentReports()).isEmpty();
        assertThat(detail.recentAdminActions()).isEmpty();
    }

    @Test
    @DisplayName("detail - recentPenalties projection from V8 column (CREW + ADMIN both surfaced)")
    void detail_recentPenalties_projection() {
        UserAccountData hostAccount = UserAccountData.createForLocal(HOST_UID, "host@example.com", "hash");
        MemberData hostMember = memberWithNickname(7L, "hostNick");

        // V8: 2 history rows, one CREW (with punisher_crew_id), one ADMIN (punisher_crew_id=null).
        CrewPenaltyHistoryData crewApplied = CrewPenaltyHistoryData.builder()
                .id(501L)
                .partyroomId(PID)
                .punishedCrewId(new CrewId(5001L))
                .punisherCrewId(new CrewId(5002L))
                .punisherType(PunisherType.CREW)
                .penaltyType(PenaltyType.PERMANENT_EXPULSION)
                .penaltyReason("crew-rationale")
                .penaltyDate(LocalDateTime.of(2026, 4, 27, 9, 30))
                .released(false)
                .build();
        CrewPenaltyHistoryData adminApplied = CrewPenaltyHistoryData.builder()
                .id(502L)
                .partyroomId(PID)
                .punishedCrewId(new CrewId(5003L))
                .punisherCrewId(null)
                .punisherType(PunisherType.ADMIN)
                .penaltyType(PenaltyType.PERMANENT_EXPULSION)
                .penaltyReason("admin-rationale")
                .penaltyDate(LocalDateTime.of(2026, 4, 27, 10, 0))
                .released(false)
                .build();

        when(aggregatePort.findPartyroomById(PID.getId())).thenReturn(Optional.of(partyroom));
        when(userAccountRepository.findById(HOST_UID)).thenReturn(Optional.of(hostAccount));
        when(memberRepository.findByUserAccountId(HOST_UID.getUid())).thenReturn(Optional.of(hostMember));
        when(aggregatePort.findActiveCrews(PID)).thenReturn(List.of());
        when(aggregatePort.findDjsOrdered(PID)).thenReturn(List.of());
        when(adminActionRepository.findTop10ByPartyroomIdOrderByOccurredAtDesc(PID.getId()))
                .thenReturn(List.of());
        when(aggregatePort.findPlaybackState(PID)).thenReturn(PartyroomPlaybackData.createFor(PID));
        when(crewPenaltyHistoryRepository.findTop5ByPartyroomIdOrderByPenaltyDateDesc(PID))
                .thenReturn(List.of(adminApplied, crewApplied));   // repository returns desc-ordered

        AdminPartyroomDetailResponse detail = service.detail(PID);

        assertThat(detail.recentPenalties()).hasSize(2);
        AdminPartyroomDetailResponse.PenaltySummary first = detail.recentPenalties().get(0);
        assertThat(first.id()).isEqualTo(502L);
        assertThat(first.crewId()).isEqualTo(5003L);
        assertThat(first.penaltyType()).isEqualTo(PenaltyType.PERMANENT_EXPULSION);
        assertThat(first.punisherType()).isEqualTo("ADMIN");
        assertThat(first.reason()).isEqualTo("admin-rationale");
        assertThat(first.date()).isEqualTo(LocalDateTime.of(2026, 4, 27, 10, 0));

        AdminPartyroomDetailResponse.PenaltySummary second = detail.recentPenalties().get(1);
        assertThat(second.id()).isEqualTo(501L);
        assertThat(second.crewId()).isEqualTo(5001L);
        assertThat(second.punisherType()).isEqualTo("CREW");
        assertThat(second.reason()).isEqualTo("crew-rationale");
    }

    @Test
    @DisplayName("detail - room not found → PartyroomException(NOT_FOUND_ROOM); no downstream queries")
    void detail_not_found() {
        when(aggregatePort.findPartyroomById(PID.getId())).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.detail(PID))
                .isInstanceOf(NotFoundException.class)
                .hasMessageContaining("파티룸을 찾을 수 없습니다");

        verify(userAccountRepository, never()).findById(any());
        verify(memberRepository, never()).findByUserAccountId(any());
        verify(aggregatePort, never()).findActiveCrews(any());
        verify(aggregatePort, never()).findDjsOrdered(any());
        verify(aggregatePort, never()).findPlaybackState(any());
        verify(adminActionRepository, never()).findTop10ByPartyroomIdOrderByOccurredAtDesc(any());
    }

    // ── Helpers ──

    private static MemberData memberWithNickname(Long userAccountId, String nickname) {
        MemberData m = MemberData.createForUserAccount(userAccountId);
        ProfileData p = ProfileData.builder()
                .userId(new UserId(userAccountId))
                .build();
        // ProfileData.builder() creates Bio with null nickname; updateBio handles null bio.
        p.updateBio(nickname, null);
        m.initializeProfile(p);
        return m;
    }

    private static void setField(Object target, String fieldName, Object value) {
        try {
            Field f = findField(target.getClass(), fieldName);
            f.setAccessible(true);
            f.set(target, value);
        } catch (Exception e) {
            throw new RuntimeException("Failed to set field " + fieldName, e);
        }
    }

    private static Field findField(Class<?> cls, String name) throws NoSuchFieldException {
        Class<?> c = cls;
        while (c != null) {
            try {
                return c.getDeclaredField(name);
            } catch (NoSuchFieldException ignored) {
                c = c.getSuperclass();
            }
        }
        throw new NoSuchFieldException(name);
    }
}
