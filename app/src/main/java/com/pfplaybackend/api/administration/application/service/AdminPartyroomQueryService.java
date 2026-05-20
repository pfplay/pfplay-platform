package com.pfplaybackend.api.administration.application.service;

import com.pfplaybackend.api.administration.adapter.in.web.payload.response.AdminPartyroomDetailResponse;
import com.pfplaybackend.api.administration.adapter.in.web.payload.response.AdminPartyroomDetailResponse.AdminActionSummary;
import com.pfplaybackend.api.administration.adapter.in.web.payload.response.AdminPartyroomDetailResponse.CrewSummary;
import com.pfplaybackend.api.administration.adapter.in.web.payload.response.AdminPartyroomDetailResponse.DjSummary;
import com.pfplaybackend.api.administration.adapter.in.web.payload.response.AdminPartyroomDetailResponse.PlaybackSummary;
import com.pfplaybackend.api.administration.adapter.in.web.payload.response.AdminPartyroomListItemResponse;
import com.pfplaybackend.api.administration.adapter.out.persistence.AdminPartyroomQueryRepository;
import com.pfplaybackend.api.administration.adapter.out.persistence.PartyroomAdminActionRepository;
import com.pfplaybackend.api.administration.application.dto.AdminPartyroomListFilter;
import com.pfplaybackend.api.administration.application.dto.AdminPartyroomListRow;
import com.pfplaybackend.api.administration.domain.entity.data.PartyroomAdminActionData;
import com.pfplaybackend.api.common.domain.value.UserId;
import com.pfplaybackend.api.common.exception.ExceptionCreator;
import com.pfplaybackend.api.party.domain.entity.data.CrewData;
import com.pfplaybackend.api.party.domain.entity.data.DjData;
import com.pfplaybackend.api.party.adapter.out.persistence.CrewPenaltyHistoryRepository;
import com.pfplaybackend.api.party.domain.entity.data.PartyroomData;
import com.pfplaybackend.api.party.domain.entity.data.PartyroomPlaybackData;
import com.pfplaybackend.api.party.domain.exception.PartyroomException;
import com.pfplaybackend.api.party.domain.port.PartyroomAggregatePort;
import com.pfplaybackend.api.party.domain.value.PartyroomId;
import com.pfplaybackend.api.user.adapter.out.persistence.MemberRepository;
import com.pfplaybackend.api.user.adapter.out.persistence.UserAccountRepository;
import com.pfplaybackend.api.user.domain.entity.data.MemberData;
import com.pfplaybackend.api.user.domain.entity.data.UserAccountData;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Read-only query service for the admin partyroom views (B-1 list, B-2 detail).
 *
 * <p>Cross-BC reads go through {@link PartyroomAggregatePort} (Party) and
 * {@link UserAccountRepository}/{@link MemberRepository} (User) — Administration BC
 * never touches Party/User entity types except inside its repository adapters.
 * ArchUnit (Task 18) enforces that boundary.
 *
 * <p>The {@code list} method delegates to {@link AdminPartyroomQueryRepository},
 * which performs a single cross-BC JOIN. The {@code detail} method intentionally
 * composes 5–6 small sub-queries (instead of one mega-JOIN) to keep cross-BC
 * coupling explicit and minimal — see plan §5.
 *
 * <p>PR 8 MVP scope: {@code recentReports} (PR 13), {@code currentTrackName},
 * and {@code playlistName} (Playlist module) are returned as null/empty per spec §2.2.
 * PR 9: {@code recentPenalties} now populated from {@code CREW_PENALTY_HISTORY}
 * V8 column (top 5, penalty_date desc; CREW + ADMIN both surfaced).
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class AdminPartyroomQueryService {

    private final AdminPartyroomQueryRepository adminPartyroomQueryRepository;
    private final PartyroomAggregatePort aggregatePort;
    private final UserAccountRepository userAccountRepository;
    private final MemberRepository memberRepository;
    private final PartyroomAdminActionRepository adminActionRepository;
    private final CrewPenaltyHistoryRepository crewPenaltyHistoryRepository;

    public Page<AdminPartyroomListItemResponse> list(AdminPartyroomListFilter filter, Pageable pageable) {
        return adminPartyroomQueryRepository.findAdminList(filter, pageable)
                .map(this::toListItem);
    }

    public AdminPartyroomDetailResponse detail(PartyroomId partyroomId) {
        // 1. Root partyroom — 404 if missing.
        PartyroomData partyroom = aggregatePort.findPartyroomById(partyroomId.getId())
                .orElseThrow(() -> ExceptionCreator.create(PartyroomException.NOT_FOUND_ROOM));

        // 2. Host user account (null-tolerant — host row may have been hard-deleted in test data).
        UserId hostUserId = partyroom.getHostId();
        UserAccountData hostAccount = hostUserId == null
                ? null
                : userAccountRepository.findById(hostUserId).orElse(null);

        // 3. Host member profile (null-tolerant — host may not have completed profile).
        MemberData hostMember = (hostUserId == null)
                ? null
                : memberRepository.findByUserAccountId(hostUserId.getUid()).orElse(null);

        // 4. Active crews + bulk member fetch for nicknames.
        List<CrewData> activeCrews = aggregatePort.findActiveCrews(partyroomId);
        Map<Long, MemberData> membersByUserId = activeCrews.isEmpty()
                ? Map.of()
                : memberRepository.findAllByUserAccountIdIn(
                            activeCrews.stream()
                                    .map(c -> c.getUserId().getUid())
                                    .toList()
                    ).stream()
                    .collect(Collectors.toMap(MemberData::getUserAccountId, m -> m, (a, b) -> a));

        List<CrewSummary> crewSummaries = activeCrews.stream()
                .map(c -> {
                    MemberData m = membersByUserId.get(c.getUserId().getUid());
                    return new CrewSummary(
                            c.getId(),
                            m == null ? null : m.getMemberId(),
                            c.getGradeType(),
                            extractNickname(m),
                            c.getEnteredAt()
                    );
                })
                .toList();

        // 5. DJ queue (ordered).
        List<DjData> djs = aggregatePort.findDjsOrdered(partyroomId);
        List<DjSummary> djSummaries = djs.stream()
                .map(d -> new DjSummary(
                        d.getId(),
                        d.getCrewId() == null ? null : d.getCrewId().getId(),
                        null,                       // playlistName — Playlist port out of scope (PR 8 MVP)
                        d.getOrderNumber()
                ))
                .toList();

        // 6a. Recent penalties — PR 9 V8 컬럼에서 채움 (released 포함, penalty_date desc top 5).
        List<AdminPartyroomDetailResponse.PenaltySummary> recentPenalties =
                crewPenaltyHistoryRepository.findTop5ByPartyroomIdOrderByPenaltyDateDesc(partyroomId)
                        .stream()
                        .map(h -> new AdminPartyroomDetailResponse.PenaltySummary(
                                h.getId(),
                                h.getPunishedCrewId() == null ? null : h.getPunishedCrewId().getId(),
                                h.getPenaltyType(),
                                h.getPunisherType().name(),
                                h.getPenaltyReason(),
                                h.getPenaltyDate()))
                        .toList();

        // 6b. Recent reports — PR 13에서 채움.
        var recentReports = Collections.<AdminPartyroomDetailResponse.ReportSummary>emptyList();

        // 7. Recent admin actions (top 10, time-desc).
        List<AdminActionSummary> recentAdminActions = adminActionRepository
                .findTop10ByPartyroomIdOrderByOccurredAtDesc(partyroomId.getId())
                .stream()
                .map(this::toAdminActionSummary)
                .toList();

        // 8. Playback state.
        PartyroomPlaybackData playback = aggregatePort.findPlaybackState(partyroomId);
        PlaybackSummary playbackSummary = toPlaybackSummary(playback);

        // PR 14g §5: introduction + playbackTimeLimit root-level 노출 — frontend update-meta
        // dialog placeholder + detail card 활성화. PlaybackTimeLimit VO는 minutes로 평탄화 (null tolerant).
        Integer playbackLimitMinutes = partyroom.getPlaybackTimeLimit() == null
                ? null
                : partyroom.getPlaybackTimeLimit().getMinutes();

        return new AdminPartyroomDetailResponse(
                partyroom.getId(),
                partyroom.getTitle(),
                partyroom.getIntroduction(),
                partyroom.getStatus(),
                partyroom.getDisplayFlag(),
                hostUserId == null ? null : hostUserId.getUid(),
                extractNickname(hostMember),
                hostAccount == null ? null : hostAccount.getEmail(),
                partyroom.getActiveCrewCount(),
                partyroom.getLastActivityAt(),
                partyroom.getStageType(),
                playbackLimitMinutes,
                playbackSummary,
                crewSummaries,
                djSummaries,
                recentPenalties,
                recentReports,
                recentAdminActions
        );
    }

    // ── Mappers ──

    private AdminPartyroomListItemResponse toListItem(AdminPartyroomListRow r) {
        return new AdminPartyroomListItemResponse(
                r.partyroomId(),
                r.title(),
                r.stageType(),
                r.hostUserAccountId(),
                r.hostNickname(),
                r.crewCount(),
                r.djCount(),
                Boolean.TRUE.equals(r.playbackActivated()),    // null PARTYROOM_PLAYBACK row → false
                r.status(),
                r.displayFlag(),
                r.createdAt(),
                r.lastActivityAt()
        );
    }

    private AdminActionSummary toAdminActionSummary(PartyroomAdminActionData d) {
        return new AdminActionSummary(
                d.getId(),
                d.getActionType(),
                d.getAdministratorId(),
                d.getOccurredAt(),
                d.getMetadata()
        );
    }

    private PlaybackSummary toPlaybackSummary(PartyroomPlaybackData p) {
        if (p == null) {
            return new PlaybackSummary(false, null, null);
        }
        return new PlaybackSummary(
                p.isActivated(),
                null,                                                  // currentTrackName — Playlist port out of scope (PR 8 MVP)
                p.getCurrentDjCrewId() == null ? null : p.getCurrentDjCrewId().getId()
        );
    }

    private static String extractNickname(MemberData member) {
        if (member == null || member.getProfileData() == null) {
            return null;
        }
        return member.getProfileData().getNicknameValue();
    }
}
