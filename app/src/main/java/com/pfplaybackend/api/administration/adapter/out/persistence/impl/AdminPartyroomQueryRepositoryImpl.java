package com.pfplaybackend.api.administration.adapter.out.persistence.impl;

import com.pfplaybackend.api.administration.adapter.out.persistence.AdminPartyroomQueryRepository;
import com.pfplaybackend.api.administration.adapter.in.web.payload.response.AdminPartyroomListItemResponse.VirtualDjSummary;
import com.pfplaybackend.api.administration.application.dto.AdminPartyroomListFilter;
import com.pfplaybackend.api.administration.application.dto.AdminPartyroomListRow;
import com.pfplaybackend.api.party.domain.entity.data.QCrewData;
import com.pfplaybackend.api.party.domain.entity.data.QDjData;
import com.pfplaybackend.api.party.domain.entity.data.QPartyroomData;
import com.pfplaybackend.api.party.domain.entity.data.QPartyroomPlaybackData;
import com.pfplaybackend.api.party.domain.enums.PartyroomStatus;
import com.pfplaybackend.api.user.domain.entity.data.QMemberData;
import com.pfplaybackend.api.user.domain.entity.data.QProfileData;
import com.pfplaybackend.api.user.domain.entity.data.QUserAccountData;
import com.pfplaybackend.api.user.domain.value.Nickname;
import com.pfplaybackend.api.virtualdj.domain.entity.data.QPartyroomVirtualDjConfigData;
import com.pfplaybackend.api.virtualdj.domain.enums.VirtualDjStatus;
import com.querydsl.core.BooleanBuilder;
import com.querydsl.core.Tuple;
import com.querydsl.core.types.dsl.ComparableExpressionBase;
import com.querydsl.core.types.dsl.Expressions;
import com.querydsl.core.types.dsl.StringExpression;
import com.querydsl.jpa.JPAExpressions;
import com.querydsl.jpa.JPQLQuery;
import com.querydsl.jpa.impl.JPAQuery;
import com.querydsl.jpa.impl.JPAQueryFactory;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * QueryDSL implementation of {@link AdminPartyroomQueryRepository}.
 *
 * <p>Cross-BC JOIN: PARTYROOM + USER_ACCOUNT + MEMBER (+ PROFILE for nickname)
 * + PARTYROOM_PLAYBACK + DJ (count subquery). This is the only place inside
 * the Administration BC that references Party/User entity types directly;
 * ArchUnit (Task 18) enforces this isolation.
 *
 * <p>Nickname path strategy: {@code Bio.nickname} is annotated with
 * {@code @Convert(NicknameConverter.class)} and the generated Q-class exposes
 * it as {@code SimplePath<Nickname>}. Two failure modes follow:
 * <ul>
 *   <li>Direct projection of the path comes back as a {@link Nickname} VO
 *       (Hibernate applies the converter on read), which would crash the
 *       record's {@code String hostNickname} constructor parameter. We
 *       instead project a {@link Tuple} carrying the raw {@code Nickname}
 *       and unwrap via {@link Nickname#value()} on the Java side.</li>
 *   <li>Calling {@code .like(String)} directly on the path tries to bind the
 *       String wildcard against a parameter typed as {@code Nickname}. We
 *       wrap the path in a {@code cast(... as string)} {@link
 *       Expressions#stringTemplate(String, Object...) stringTemplate} so the
 *       SQL layer sees a {@code String} expression and accepts the
 *       {@code String} bind. The same template doubles as the order-by path
 *       when sorting by {@code hostNickname}.</li>
 * </ul>
 */
@Repository
@RequiredArgsConstructor
public class AdminPartyroomQueryRepositoryImpl implements AdminPartyroomQueryRepository {

    private final JPAQueryFactory queryFactory;

    @Override
    public Page<AdminPartyroomListRow> findAdminList(AdminPartyroomListFilter filter, Pageable pageable) {
        QPartyroomData p = QPartyroomData.partyroomData;
        QUserAccountData ua = QUserAccountData.userAccountData;
        QMemberData m = QMemberData.memberData;
        QProfileData profile = QProfileData.profileData;
        QDjData dj = QDjData.djData;
        QPartyroomPlaybackData pb = QPartyroomPlaybackData.partyroomPlaybackData;
        // P2 Task 2.2: 가상 DJ config (left join) + 봇 DJ 카운트(상관 서브쿼리).
        QPartyroomVirtualDjConfigData cfg = QPartyroomVirtualDjConfigData.partyroomVirtualDjConfigData;
        // 서브쿼리 전용 alias — 외부 `ua`(host user_account)와 충돌하지 않도록 별도 인스턴스를 둔다.
        QCrewData botCrew = new QCrewData("botCrew");
        QUserAccountData botUa = new QUserAccountData("botUa");

        // Path-projecting m.profileData.bio.nickname forces an implicit INNER JOIN
        // on profile, which silently excludes any partyroom whose host has
        // member.profile_id=NULL (e.g., super-admin pre-PA-7 fix). We declare the
        // join explicitly as LEFT so a missing profile yields a null nickname
        // rather than dropping the row. Reading the projection through `profile.*`
        // (rather than `m.profileData.*`) keeps the path bound to that LEFT JOIN.
        StringExpression nicknameLikeExpr = Expressions.stringTemplate(
                "cast({0} as string)", profile.bio.nickname);

        JPQLQuery<Long> djCountSubquery = JPAExpressions
                .select(dj.count())
                .from(dj)
                .where(dj.partyroomId.id.eq(p.id));

        // 봇 DJ 카운트: DJ → CrewData(is_active 무관, snapshot 의미는 ActiveDjSnapshot 과 동일한
        // crew_id 조인키) → user_account(is_dummy=true). DjData 에는 userId 가 없어 crew 를 경유한다.
        // 조인키는 ActiveDjSnapshotQueryRepositoryImpl 의 canonical 조인을 그대로 미러링한다.
        QDjData botDj = new QDjData("botDj");
        JPQLQuery<Long> botDjCountSubquery = JPAExpressions
                .select(botDj.count())
                .from(botDj)
                .join(botCrew).on(botCrew.id.eq(botDj.crewId.id))
                .join(botUa).on(botUa.userId.uid.eq(botCrew.userId.uid))
                .where(botDj.partyroomId.id.eq(p.id).and(botUa.isDummy.isTrue()));

        BooleanBuilder where = buildPredicates(filter, p, ua, nicknameLikeExpr);

        JPAQuery<Tuple> query = queryFactory
                .select(
                        p.id,
                        p.title,
                        p.stageType,
                        ua.userId.uid,
                        profile.bio.nickname,
                        p.activeCrewCount,
                        djCountSubquery,
                        pb.isActivated,
                        p.status,
                        p.displayFlag,
                        p.createdAt,
                        p.lastActivityAt,
                        cfg.status,
                        cfg.targetCount,
                        botDjCountSubquery
                )
                .from(p)
                .leftJoin(ua).on(ua.userId.uid.eq(p.hostId.uid))
                .leftJoin(m).on(m.userAccountId.eq(ua.userId.uid))
                .leftJoin(m.profileData, profile)
                .leftJoin(pb).on(pb.partyroomId.id.eq(p.id))
                .leftJoin(cfg).on(cfg.partyroomId.eq(p.id))
                .where(where);

        applySort(query, pageable.getSort(), p, nicknameLikeExpr);

        Long total = queryFactory
                .select(p.count())
                .from(p)
                .leftJoin(ua).on(ua.userId.uid.eq(p.hostId.uid))
                .leftJoin(m).on(m.userAccountId.eq(ua.userId.uid))
                .leftJoin(m.profileData, profile)
                .where(where)
                .fetchOne();

        List<Tuple> tuples = query
                .offset(pageable.getOffset())
                .limit(pageable.getPageSize())
                .fetch();

        List<AdminPartyroomListRow> content = tuples.stream()
                .map(t -> mapRow(t, p, ua, profile, pb, djCountSubquery, cfg, botDjCountSubquery))
                .toList();

        return new PageImpl<>(content, pageable, total == null ? 0L : total);
    }

    private AdminPartyroomListRow mapRow(Tuple t,
                                         QPartyroomData p,
                                         QUserAccountData ua,
                                         QProfileData profile,
                                         QPartyroomPlaybackData pb,
                                         JPQLQuery<Long> djCountSubquery,
                                         QPartyroomVirtualDjConfigData cfg,
                                         JPQLQuery<Long> botDjCountSubquery) {
        Nickname nickname = t.get(profile.bio.nickname);
        Long djCount = t.get(djCountSubquery);
        Integer crewCount = t.get(p.activeCrewCount);
        // 가상 DJ 요약: config row 가 없으면 left join 으로 cfg.status 가 null → virtualDj=null.
        VirtualDjStatus vdjStatus = t.get(cfg.status);
        VirtualDjSummary virtualDj = null;
        if (vdjStatus != null) {
            Long botDjCount = t.get(botDjCountSubquery);
            virtualDj = new VirtualDjSummary(
                    vdjStatus,
                    t.get(cfg.targetCount),
                    botDjCount == null ? 0L : botDjCount
            );
        }
        return new AdminPartyroomListRow(
                t.get(p.id),
                t.get(p.title),
                t.get(p.stageType),
                t.get(ua.userId.uid),
                nickname == null ? null : nickname.value(),
                crewCount == null ? 0 : crewCount,
                djCount == null ? 0L : djCount,
                t.get(pb.isActivated),
                t.get(p.status),
                t.get(p.displayFlag),
                t.get(p.createdAt),
                t.get(p.lastActivityAt),
                virtualDj
        );
    }

    /**
     * status: null → exclude TERMINATED (Risk #6 admin usability); otherwise exact match.
     * stageType / createdFrom / createdTo: null → no constraint.
     * hostQuery: blank → no constraint; non-blank → email LIKE OR nickname LIKE.
     */
    private BooleanBuilder buildPredicates(AdminPartyroomListFilter f,
                                           QPartyroomData p,
                                           QUserAccountData ua,
                                           StringExpression nicknameExpr) {
        BooleanBuilder b = new BooleanBuilder();
        if (f.status() != null) {
            b.and(p.status.eq(f.status()));
        } else {
            b.and(p.status.ne(PartyroomStatus.TERMINATED));
        }
        if (f.stageType() != null) {
            b.and(p.stageType.eq(f.stageType()));
        }
        if (f.createdFrom() != null) {
            b.and(p.createdAt.goe(f.createdFrom()));
        }
        if (f.createdTo() != null) {
            b.and(p.createdAt.lt(f.createdTo()));
        }
        if (f.hostQuery() != null && !f.hostQuery().isBlank()) {
            String like = "%" + f.hostQuery() + "%";
            b.and(ua.email.like(like).or(nicknameExpr.like(like)));
        }
        return b;
    }

    /**
     * Whitelisted sort fields. Unknown property → IllegalArgumentException;
     * the application-layer service is expected to surface this as 400 to the
     * caller (rather than silently ignore the requested order).
     */
    private void applySort(JPAQuery<?> query, Sort sort,
                           QPartyroomData p, StringExpression nicknameExpr) {
        if (sort.isUnsorted()) {
            query.orderBy(p.createdAt.desc());
            return;
        }
        for (Sort.Order order : sort) {
            ComparableExpressionBase<?> path = switch (order.getProperty()) {
                case "createdAt"      -> p.createdAt;
                case "lastActivityAt" -> p.lastActivityAt;
                case "crewCount"      -> p.activeCrewCount;
                case "title"          -> p.title;
                case "hostNickname"   -> nicknameExpr;
                default -> throw new IllegalArgumentException(
                        "Unsupported sort field: " + order.getProperty());
            };
            query.orderBy(order.isAscending() ? path.asc() : path.desc());
        }
    }
}
