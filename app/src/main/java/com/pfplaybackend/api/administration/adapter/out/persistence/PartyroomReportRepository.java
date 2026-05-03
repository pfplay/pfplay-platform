package com.pfplaybackend.api.administration.adapter.out.persistence;

import com.pfplaybackend.api.administration.domain.entity.data.PartyroomReportData;
import com.pfplaybackend.api.administration.domain.enums.ReportCategory;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDateTime;

/**
 * PartyroomReport 저장소.
 *
 * 24h 중복 검증 method query (G1) + 어드민 C-2 list QueryDSL custom (G3).
 */
public interface PartyroomReportRepository
        extends JpaRepository<PartyroomReportData, Long>, PartyroomReportRepositoryCustom {

    /**
     * 동일 reporter가 동일 partyroom을 동일 category로 since(보통 now-24h) 이후 신고했는지.
     * D1 결정에 따른 앱 검증.
     */
    boolean existsByReporterUserAccountIdAndPartyroomIdAndCategoryAndCreatedAtAfter(
            Long reporterUserAccountId,
            Long partyroomId,
            ReportCategory category,
            LocalDateTime since);
}
