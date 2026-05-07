package com.pfplaybackend.api.administration.application.service;

import com.pfplaybackend.api.administration.adapter.in.web.dto.AdminReportDetailResponse;
import com.pfplaybackend.api.administration.adapter.in.web.dto.AdminReportDetailResponse.Host;
import com.pfplaybackend.api.administration.adapter.in.web.dto.AdminReportDetailResponse.Partyroom;
import com.pfplaybackend.api.administration.adapter.in.web.dto.AdminReportDetailResponse.Reporter;
import com.pfplaybackend.api.administration.adapter.in.web.dto.AdminReportDetailResponse.Review;
import com.pfplaybackend.api.administration.adapter.in.web.dto.AdminReportListQuery;
import com.pfplaybackend.api.administration.adapter.in.web.dto.AdminReportSummaryResponse;
import com.pfplaybackend.api.administration.adapter.out.persistence.PartyroomReportRepository;
import com.pfplaybackend.api.administration.domain.entity.data.PartyroomReportData;
import com.pfplaybackend.api.administration.domain.exception.AdminReportException;
import com.pfplaybackend.api.common.domain.value.UserId;
import com.pfplaybackend.api.common.exception.ExceptionCreator;
import com.pfplaybackend.api.party.domain.entity.data.PartyroomData;
import com.pfplaybackend.api.party.domain.port.PartyroomAggregatePort;
import com.pfplaybackend.api.user.adapter.out.persistence.MemberRepository;
import com.pfplaybackend.api.user.adapter.out.persistence.UserAccountRepository;
import com.pfplaybackend.api.user.domain.entity.data.MemberData;
import com.pfplaybackend.api.user.domain.entity.data.UserAccountData;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * C-2 read-only query service — admin 신고 list/detail.
 *
 * <p>Detail은 {@link PartyroomReportData} aggregate(administration BC)에 cross-context loose-ref
 * 4종을 합성한다:
 * <ul>
 *     <li>Reporter member nickname — {@code MemberRepository.findByUserAccountId(reporterUserAccountId)}</li>
 *     <li>Reporter user_account email — {@code UserAccountRepository.findById(new UserId(uid))}</li>
 *     <li>Partyroom title — {@code PartyroomAggregatePort.findPartyroomById(partyroomId)}</li>
 *     <li>Host member nickname — {@code MemberRepository.findByUserAccountId(hostUserAccountId)}</li>
 * </ul>
 * 외부 BC 엔티티 부재 시 해당 필드를 {@code null}로 채운다 (orphan tolerance — D7).
 *
 * <p>Cross-BC entity는 hexagonal port({@link PartyroomAggregatePort}) 또는 user 모듈 repository를
 * 통해 read-only로 접근. 직접 inject 정책은 {@link com.pfplaybackend.api.administration.application.service.PartyroomReportCommandService}
 * 와 동형(C-1 G2 패턴).
 *
 * <p>{@link #buildDetailResponse(PartyroomReportData)} 메서드는 G4 PATCH command service에서
 * 재사용된다 (status 전이 후 응답 shape 동일).
 *
 * <p>Spec: docs/superpowers/specs/2026-04-28-admin-platform-pr13-design.md §3.2, §3.3, §5
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class AdminReportQueryService {

    private final PartyroomReportRepository reportRepository;
    private final MemberRepository memberRepository;
    private final UserAccountRepository userAccountRepository;
    private final PartyroomAggregatePort partyroomAggregatePort;

    public Page<AdminReportSummaryResponse> getList(AdminReportListQuery query, Pageable pageable) {
        return reportRepository.findList(
                        query.statuses(),
                        query.categories(),
                        query.createdFrom(),
                        query.createdTo(),
                        pageable)
                .map(AdminReportSummaryResponse::from);
    }

    public AdminReportDetailResponse getDetail(Long reportId) {
        PartyroomReportData report = reportRepository.findById(reportId)
                .orElseThrow(() -> ExceptionCreator.create(AdminReportException.REPORT_NOT_FOUND));
        return buildDetailResponse(report);
    }

    /**
     * Public entry-point — G4 PATCH command service에서 status 전이 후 detail 재계산용으로 재사용.
     * 입력 {@code report}는 영속화 후 mutation까지 반영된 상태여야 한다 (호출자 책임).
     */
    public AdminReportDetailResponse buildDetailResponse(PartyroomReportData report) {
        Reporter reporter = buildReporter(report.getReporterUserAccountId());
        Partyroom partyroom = buildPartyroom(report.getPartyroomId());
        Review review = new Review(
                report.getReviewedByAdministratorId(),
                report.getResolutionNote(),
                report.getResolvedAt());
        return new AdminReportDetailResponse(
                report.getReportId(),
                report.getStatus(),
                report.getCategory(),
                report.getDescription(),
                reporter,
                partyroom,
                review,
                report.getCreatedAt());
    }

    private Reporter buildReporter(Long userAccountId) {
        String email = userAccountRepository.findById(new UserId(userAccountId))
                .map(UserAccountData::getEmail)
                .orElse(null);
        String nickname = memberRepository.findByUserAccountId(userAccountId)
                .map(AdminReportQueryService::nicknameOf)
                .orElse(null);
        return new Reporter(userAccountId, email, nickname);
    }

    private Partyroom buildPartyroom(Long partyroomId) {
        return partyroomAggregatePort.findPartyroomById(partyroomId)
                .map(p -> new Partyroom(partyroomId, p.getTitle(), buildHostFromPartyroom(p)))
                .orElse(new Partyroom(partyroomId, null, null));
    }

    private Host buildHostFromPartyroom(PartyroomData partyroom) {
        if (partyroom.getHostId() == null || partyroom.getHostId().getUid() == null) {
            return null;
        }
        Long hostUid = partyroom.getHostId().getUid();
        String nickname = memberRepository.findByUserAccountId(hostUid)
                .map(AdminReportQueryService::nicknameOf)
                .orElse(null);
        return new Host(hostUid, nickname);
    }

    /**
     * MemberData → nickname 추출. profileData/bio가 부재하거나 nickname이 미설정이면 null.
     */
    private static String nicknameOf(MemberData member) {
        if (member.getProfileData() == null || member.getProfileData().getBio() == null) {
            return null;
        }
        return member.getProfileData().getBio().getNicknameValue();
    }
}
