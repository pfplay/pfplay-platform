package com.pfplaybackend.api.administration.adapter.out.persistence;

import com.pfplaybackend.api.administration.application.dto.DailyAttendanceBucket;
import com.pfplaybackend.api.administration.domain.entity.QUserActivityLogData;
import com.pfplaybackend.api.administration.domain.enums.UserActivityEventType;
import com.querydsl.core.types.dsl.CaseBuilder;
import com.querydsl.core.types.dsl.DateTemplate;
import com.querydsl.core.types.dsl.Expressions;
import com.querydsl.core.types.dsl.NumberExpression;
import com.querydsl.jpa.impl.JPAQueryFactory;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;

/**
 * user_activity_log 파티룸 입퇴장 집계 리포지토리 (QueryDSL).
 *
 * <p>어드민 파티룸 행동분석 ② "입장/퇴장 집계" + 침묵지표가 소비하는 EXIT 타임스탬프 목록을 생산한다.
 * partyroom_id(plain Long) + occurred_at 범위 [from, now) 로 키잉.
 * V34 인덱스 {@code idx_ual_partyroom_event_time(partyroom_id, event_type, occurred_at DESC)} 를 탄다.
 *
 * <p>eventType 은 VARCHAR(64) 컬럼(enum.name() 직렬화)이라 String 비교.
 *
 * Spec: docs/superpowers/specs/2026-07-09-admin-partyroom-behavior-analytics-design.md
 */
@Repository
@RequiredArgsConstructor
public class UserActivityLogAnalyticsRepository {

    private static final String ENTERED = UserActivityEventType.PARTYROOM_ENTERED.name();
    private static final String EXITED  = UserActivityEventType.PARTYROOM_EXITED.name();

    private final JPAQueryFactory queryFactory;

    /** 일자별 entered/exited. date = occurred_at 벽시계 달력일(DATE()). 발생한 날만, asc. */
    public List<DailyAttendanceBucket> findDailyAttendance(Long partyroomId, LocalDateTime from, LocalDateTime now) {
        QUserActivityLogData q = QUserActivityLogData.userActivityLogData;
        DateTemplate<java.sql.Date> day = Expressions.dateTemplate(java.sql.Date.class, "DATE({0})", q.occurredAt);
        NumberExpression<Integer> enteredCase = new CaseBuilder().when(q.eventType.eq(ENTERED)).then(1).otherwise(0).sum();
        NumberExpression<Integer> exitedCase  = new CaseBuilder().when(q.eventType.eq(EXITED)).then(1).otherwise(0).sum();
        return queryFactory
                .select(day, enteredCase, exitedCase)
                .from(q)
                .where(q.partyroomId.eq(partyroomId)
                        .and(q.eventType.in(ENTERED, EXITED))
                        .and(q.occurredAt.goe(from))
                        .and(q.occurredAt.lt(now)))
                .groupBy(day)
                .orderBy(day.asc())
                .fetch()
                .stream()
                .map(t -> new DailyAttendanceBucket(t.get(day).toLocalDate(), nz(t.get(enteredCase)), nz(t.get(exitedCase))))
                .toList();
    }

    /** 윈도우 내 ENTERED distinct user 수. */
    public long countUniqueVisitors(Long partyroomId, LocalDateTime from, LocalDateTime now) {
        QUserActivityLogData q = QUserActivityLogData.userActivityLogData;
        Long c = queryFactory.select(q.userAccountId.countDistinct()).from(q)
                .where(q.partyroomId.eq(partyroomId).and(q.eventType.eq(ENTERED))
                        .and(q.occurredAt.goe(from)).and(q.occurredAt.lt(now)))
                .fetchOne();
        return c == null ? 0L : c;
    }

    /** 윈도우 내 EXITED occurred_at 목록 (침묵지표 소비용). */
    public List<LocalDateTime> findExitOccurredAt(Long partyroomId, LocalDateTime from, LocalDateTime now) {
        QUserActivityLogData q = QUserActivityLogData.userActivityLogData;
        return queryFactory.select(q.occurredAt).from(q)
                .where(q.partyroomId.eq(partyroomId).and(q.eventType.eq(EXITED))
                        .and(q.occurredAt.goe(from)).and(q.occurredAt.lt(now)))
                .fetch();
    }

    private static int nz(Integer v) { return v == null ? 0 : v; }
}
