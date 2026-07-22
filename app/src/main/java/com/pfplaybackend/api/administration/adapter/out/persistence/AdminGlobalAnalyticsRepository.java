package com.pfplaybackend.api.administration.adapter.out.persistence;

import com.pfplaybackend.api.administration.application.dto.DailyAttendanceBucket;
import com.pfplaybackend.api.administration.application.dto.DailyUniqueVisitorsBucket;
import com.pfplaybackend.api.administration.domain.entity.QUserActivityLogData;
import com.pfplaybackend.api.administration.domain.enums.UserActivityEventType;
import com.pfplaybackend.api.party.domain.entity.data.QPartyroomData;
import com.pfplaybackend.api.party.domain.enums.PartyroomStatus;
import com.pfplaybackend.api.user.domain.entity.data.QUserAccountData;
import com.querydsl.core.BooleanBuilder;
import com.querydsl.core.types.dsl.CaseBuilder;
import com.querydsl.core.types.dsl.DateTemplate;
import com.querydsl.core.types.dsl.Expressions;
import com.querydsl.core.types.dsl.NumberExpression;
import com.querydsl.jpa.impl.JPAQuery;
import com.querydsl.jpa.impl.JPAQueryFactory;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;

/**
 * #361 전역(서비스 전체) 입퇴장 집계 리포지토리 — 어드민 대시보드 v1.
 *
 * <p>방 단위 {@link UserActivityLogAnalyticsRepository} 의 전역 변형: partyroom_id 키잉을 제거하고
 * <b>봇 제외</b>(user_activity_log × user_account, {@code is_dummy=false}) 조인을 추가한다.
 * 봇도 실유저와 동일한 tryEnter 경로로 activity 이벤트를 남기므로, 봇 배치/재배치가 전역
 * 수치를 왜곡한다 — 분석 기본값은 제외(excludeBots=true), 토글로 포함 가능.
 *
 * <p>시각 기준은 방 단위 분석과 동일: occurred_at 은 KST 로컬시각, DATE() 벽시계 달력일 버킷.
 * cross-BC 참조(party/user 엔티티)는 administration 리포지토리 계층의 기존 관례
 * ({@code AdminPartyroomQueryRepositoryImpl})를 따른다.
 */
@Repository
@RequiredArgsConstructor
public class AdminGlobalAnalyticsRepository {

    private static final String ENTERED = UserActivityEventType.PARTYROOM_ENTERED.name();
    private static final String EXITED  = UserActivityEventType.PARTYROOM_EXITED.name();

    private final JPAQueryFactory queryFactory;

    /** 일자별 entered/exited (전역). 발생한 날만, asc. */
    public List<DailyAttendanceBucket> findDailyAttendance(LocalDateTime from, LocalDateTime now, boolean excludeBots) {
        QUserActivityLogData q = QUserActivityLogData.userActivityLogData;
        DateTemplate<java.sql.Date> day = Expressions.dateTemplate(java.sql.Date.class, "DATE({0})", q.occurredAt);
        NumberExpression<Integer> enteredCase = new CaseBuilder().when(q.eventType.eq(ENTERED)).then(1).otherwise(0).sum();
        NumberExpression<Integer> exitedCase  = new CaseBuilder().when(q.eventType.eq(EXITED)).then(1).otherwise(0).sum();

        JPAQuery<?> query = queryFactory
                .select(day, enteredCase, exitedCase)
                .from(q)
                .where(baseWindow(q, from, now));
        applyBotExclusion(query, q, excludeBots);

        return query
                .groupBy(day)
                .orderBy(day.asc())
                .fetch()
                .stream()
                .map(t -> {
                    com.querydsl.core.Tuple tuple = (com.querydsl.core.Tuple) t;
                    return new DailyAttendanceBucket(
                            tuple.get(day).toLocalDate(), nz(tuple.get(enteredCase)), nz(tuple.get(exitedCase)));
                })
                .toList();
    }

    /** 일자별 순 방문자(ENTERED distinct user, 전역). 발생한 날만, asc. */
    public List<DailyUniqueVisitorsBucket> findDailyUniqueVisitors(LocalDateTime from, LocalDateTime now, boolean excludeBots) {
        QUserActivityLogData q = QUserActivityLogData.userActivityLogData;
        DateTemplate<java.sql.Date> day = Expressions.dateTemplate(java.sql.Date.class, "DATE({0})", q.occurredAt);

        JPAQuery<?> query = queryFactory
                .select(day, q.userAccountId.countDistinct())
                .from(q)
                .where(baseWindow(q, from, now).and(q.eventType.eq(ENTERED)));
        applyBotExclusion(query, q, excludeBots);

        return query
                .groupBy(day)
                .orderBy(day.asc())
                .fetch()
                .stream()
                .map(t -> {
                    com.querydsl.core.Tuple tuple = (com.querydsl.core.Tuple) t;
                    Long c = tuple.get(q.userAccountId.countDistinct());
                    return new DailyUniqueVisitorsBucket(tuple.get(day).toLocalDate(), c == null ? 0L : c);
                })
                .toList();
    }

    /** 윈도우 전체 순 방문자(ENTERED distinct user, 전역). */
    public long countUniqueVisitors(LocalDateTime from, LocalDateTime now, boolean excludeBots) {
        QUserActivityLogData q = QUserActivityLogData.userActivityLogData;
        JPAQuery<Long> query = queryFactory
                .select(q.userAccountId.countDistinct())
                .from(q)
                .where(baseWindow(q, from, now).and(q.eventType.eq(ENTERED)));
        applyBotExclusion(query, q, excludeBots);
        Long c = query.fetchOne();
        return c == null ? 0L : c;
    }

    /** 현재 ACTIVE 파티룸 수 (시계열 아님 — KPI 스냅샷). */
    public long countActiveRooms() {
        QPartyroomData p = QPartyroomData.partyroomData;
        Long c = queryFactory.select(p.count()).from(p)
                .where(p.status.eq(PartyroomStatus.ACTIVE))
                .fetchOne();
        return c == null ? 0L : c;
    }

    private BooleanBuilder baseWindow(QUserActivityLogData q, LocalDateTime from, LocalDateTime now) {
        return new BooleanBuilder()
                .and(q.eventType.in(ENTERED, EXITED))
                .and(q.occurredAt.goe(from))
                .and(q.occurredAt.lt(now));
    }

    /** excludeBots=true 면 user_account 조인으로 봇(is_dummy) 이벤트를 제외한다. */
    private void applyBotExclusion(JPAQuery<?> query, QUserActivityLogData q, boolean excludeBots) {
        if (!excludeBots) return;
        QUserAccountData ua = QUserAccountData.userAccountData;
        query.join(ua).on(ua.userId.uid.eq(q.userAccountId))
             .where(ua.isDummy.isFalse());
    }

    private static int nz(Integer v) { return v == null ? 0 : v; }
}
