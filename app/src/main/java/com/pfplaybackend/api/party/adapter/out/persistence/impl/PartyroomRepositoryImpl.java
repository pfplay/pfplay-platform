package com.pfplaybackend.api.party.adapter.out.persistence.impl;

import com.pfplaybackend.api.common.domain.value.UserId;
import com.pfplaybackend.api.party.adapter.out.persistence.custom.PartyroomRepositoryCustom;
import com.pfplaybackend.api.party.application.dto.crew.CrewDto;
import com.pfplaybackend.api.party.application.dto.partyroom.ActivePartyroomDto;
import com.pfplaybackend.api.party.application.dto.partyroom.PartyroomWithCrewDto;
import com.pfplaybackend.api.party.application.dto.playback.PlaybackDto;
import com.pfplaybackend.api.party.domain.entity.data.*;
import com.pfplaybackend.api.party.domain.enums.DisplayFlag;
import com.pfplaybackend.api.party.domain.enums.PartyroomStatus;
import com.pfplaybackend.api.party.domain.enums.StageType;
import com.pfplaybackend.api.party.domain.value.PartyroomId;
import com.querydsl.core.Tuple;
import com.querydsl.core.types.ConstructorExpression;
import com.querydsl.core.types.Projections;
import com.querydsl.jpa.JPAExpressions;
import com.querydsl.jpa.JPQLQuery;
import com.querydsl.jpa.impl.JPAQueryFactory;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

@RequiredArgsConstructor
public class PartyroomRepositoryImpl implements PartyroomRepositoryCustom {

    private final JPAQueryFactory queryFactory;
    private final Clock clock;

    @Override
    public Optional<ActivePartyroomDto> getActivePartyroomByUserId(UserId userId) {
        QCrewData qCrewData = QCrewData.crewData;
        QPartyroomPlaybackData qPlayback = QPartyroomPlaybackData.partyroomPlaybackData;
        QDjQueueData qDjQueue = QDjQueueData.djQueueData;

        ActivePartyroomDto activePartyroomDto = queryFactory
                .select(Projections.constructor(
                        ActivePartyroomDto.class,
                        // #349 id 는 CREW 의 partyroom_id 에서 직접 취득 — PARTYROOM 조인 불필요.
                        // "내 활성 방"의 단일 진실원천은 CREW 이므로, 조인 대상 부재로 crew 가 조회에서
                        // 통째로 탈락(masking)하는 경로를 원천 차단한다.
                        qCrewData.partyroomId.id,
                        // #349 LEFT JOIN 방어: DJ_QUEUE / PARTYROOM_PLAYBACK 행이 없으면(레거시·부분손상)
                        // NULL 이 primitive boolean 으로 언박싱되며 투영 시점에 NPE. coalesce(false) 로
                        // 결정적 기본값 부여 — queueClosed 는 소비처 없음, playbackActivated=false 는
                        // "활성 재생 없음"이라는 sane default (무행 방은 재생 비활성으로 표시).
                        qDjQueue.isClosed.coalesce(false),
                        qCrewData.id.as("crewId"),
                        qPlayback.isActivated.coalesce(false),
                        qPlayback.currentPlaybackId,
                        qPlayback.currentDjCrewId
                ))
                .from(qCrewData)
                // #349 INNER→LEFT: 하위행(playback/djqueue) 부재로 활성 crew 가 masking 되면 auto-exit·
                // presence 가 그 crew 를 못 봐 고아를 못 치우고(누적), 새 uk_crew_active_user 유니크와
                // 결합 시 "고아가 있으면 어느 방도 못 들어가는" 하드 wedge 로 승격된다. LEFT 로 제거.
                .leftJoin(qPlayback).on(qPlayback.partyroomId.id.eq(qCrewData.partyroomId.id))
                .leftJoin(qDjQueue).on(qDjQueue.partyroomId.id.eq(qCrewData.partyroomId.id))
                .where(qCrewData.userId.eq(userId)
                        .and(qCrewData.isActive.eq(true)))
                // #349: "유저당 활성 방 1개"는 uk_crew_active_user 유니크로 DB가 보장하므로 정상 상태에선
                // 최대 1행이다. fetchOne 은 (혹시라도 다중 활성이 남은 비정상 상태에서) 정합성 강제/조회
                // 경로 자체를 NonUniqueResultException 으로 자멸시켰다(입장 전면 wedge). 결정적 최신 1행을
                // 고르도록 orderBy + fetchFirst 로 전환 — 어떤 데이터 상태에서도 던지지 않는다.
                .orderBy(qCrewData.enteredAt.desc(), qCrewData.id.desc())
                .fetchFirst();

        return Optional.ofNullable(activePartyroomDto);
    }

    @Override
    public List<PartyroomWithCrewDto> getCrewDataByPartyroomId() {
        QPartyroomData qPartyroomData = QPartyroomData.partyroomData;
        QCrewData qCrewData = QCrewData.crewData;
        QPlaybackData qPlaybackData = QPlaybackData.playbackData;
        QPartyroomPlaybackData qPlayback = QPartyroomPlaybackData.partyroomPlaybackData;
        QDjQueueData qDjQueue = QDjQueueData.djQueueData;

        JPQLQuery<Long> crewCountSubquery = JPAExpressions
                .select(qCrewData.count())
                .from(qCrewData)
                .where(qCrewData.partyroomId.id.eq(qPartyroomData.id)
                        .and(qCrewData.isActive.eq(true))
                        .and(qCrewData.isBanned.eq(false))
                );

        ConstructorExpression<PlaybackDto> playbackDto = Projections.constructor(PlaybackDto.class,
                qPlaybackData.id,
                qPlaybackData.linkId,
                qPlaybackData.name,
                qPlaybackData.duration,
                qPlaybackData.thumbnailImage
        );

        // Fetch partyroom and crew data with crew count in a single query
        List<Tuple> tuples = queryFactory
                .select(qPartyroomData.id,
                        qPartyroomData.stageType,
                        qPartyroomData.hostId,
                        qPartyroomData.title,
                        qPartyroomData.introduction,
                        qPlayback.isActivated,
                        qDjQueue.isClosed,
                        crewCountSubquery,
                        playbackDto,
                        qCrewData.id,
                        qCrewData.userId,
                        qCrewData.gradeType,
                        qPartyroomData.createdAt
                )
                .from(qPartyroomData)
                .join(qPlayback).on(qPlayback.partyroomId.id.eq(qPartyroomData.id))
                .join(qDjQueue).on(qDjQueue.partyroomId.id.eq(qPartyroomData.id))
                .leftJoin(qCrewData)
                .on(qCrewData.partyroomId.id.eq(qPartyroomData.id)
                        .and(qCrewData.isActive.eq(true))
                        .and(qCrewData.isBanned.eq(false))
                )
                .leftJoin(qPlaybackData)
                .on(qPlaybackData.id.eq(qPlayback.currentPlaybackId.id))
                // PA-3: HIDDEN 룸은 customer list에서 제외 (진입은 link 직접 접근으로 가능 — Specification에 displayFlag 검사 없음)
                .where(
                        qPartyroomData.status.ne(PartyroomStatus.TERMINATED),
                        qPartyroomData.displayFlag.ne(DisplayFlag.HIDDEN)
                )
                .orderBy(qPartyroomData.id.asc(), qCrewData.gradeType.asc())
                .fetch();

        // Group crew data by partyroom id
        Map<Long, List<CrewDto>> crewsByPartyroomId = tuples.stream()
                .filter(tuple -> Optional.ofNullable(tuple.get(qCrewData.id)).isPresent())
                .collect(Collectors.groupingBy(
                        tuple -> Optional.ofNullable(tuple.get(qPartyroomData.id)).orElseThrow(IllegalStateException::new),
                        Collectors.mapping(tuple ->
                                new CrewDto(
                                        tuple.get(qCrewData.id),
                                        tuple.get(qCrewData.userId),
                                        tuple.get(qCrewData.gradeType)
                                ),
                                Collectors.toList()
                        )
                ));

        return new ArrayList<>(tuples.stream()
                .collect(Collectors.toMap(
                        tuple -> tuple.get(qPartyroomData.id),
                        tuple -> new PartyroomWithCrewDto(
                                tuple.get(qPartyroomData.id),
                                tuple.get(qPartyroomData.stageType),
                                tuple.get(qPartyroomData.hostId),
                                tuple.get(qPartyroomData.title),
                                tuple.get(qPartyroomData.introduction),
                                Boolean.TRUE.equals(tuple.get(qPlayback.isActivated)),
                                Boolean.TRUE.equals(tuple.get(qDjQueue.isClosed)),
                                tuple.get(crewCountSubquery),
                                tuple.get(qPartyroomData.createdAt),
                                tuple.get(8, PlaybackDto.class),
                                crewsByPartyroomId.getOrDefault(tuple.get(qPartyroomData.id), List.of())
                        ),
                        (dto1, dto2) -> dto1
                ))
                .values());
    }

    @Override
    public List<PlaybackData> getRecentPlaybackHistory(PartyroomId partyroomId) {
        QPlaybackData qPlaybackData = QPlaybackData.playbackData;

        return queryFactory
                .select(qPlaybackData)
                .from(qPlaybackData)
                .where(qPlaybackData.partyroomId.id.eq(partyroomId.getId()))
                .orderBy(qPlaybackData.createdAt.desc())
                .limit(20)
                .fetch();
    }

    @Override
    public List<PlaybackData> findPlaybackForInterval(PartyroomId partyroomId, LocalDateTime from, LocalDateTime now) {
        QPlaybackData q = QPlaybackData.playbackData;
        List<PlaybackData> inWindow = queryFactory
                .select(q).from(q)
                .where(q.partyroomId.id.eq(partyroomId.getId())
                        .and(q.createdAt.goe(from))
                        .and(q.createdAt.lt(now)))
                .orderBy(q.createdAt.asc())
                .fetch();
        PlaybackData straddle = queryFactory
                .select(q).from(q)
                .where(q.partyroomId.id.eq(partyroomId.getId())
                        .and(q.createdAt.lt(from)))
                .orderBy(q.createdAt.desc())
                .fetchFirst();
        if (straddle == null) return inWindow;
        List<PlaybackData> result = new ArrayList<>(inWindow.size() + 1);
        result.add(straddle);
        result.addAll(inWindow);
        return result;
    }

    @Override
    public Page<PlaybackData> findPlaybackHistory(PartyroomId partyroomId, Pageable pageable) {
        QPlaybackData q = QPlaybackData.playbackData;
        List<PlaybackData> content = queryFactory
                .select(q).from(q)
                .where(q.partyroomId.id.eq(partyroomId.getId()))
                .orderBy(q.createdAt.desc())            // 정렬 고정
                .offset(pageable.getOffset())
                .limit(pageable.getPageSize())
                .fetch();
        Long total = queryFactory
                .select(q.count()).from(q)
                .where(q.partyroomId.id.eq(partyroomId.getId()))
                .fetchOne();
        return new PageImpl<>(content, pageable, total == null ? 0L : total);
    }

    @Override
    public List<PartyroomData> findAllUnusedPartyroomDataByDay(int days) {
        QPartyroomData qPartyroomData = QPartyroomData.partyroomData;

        return queryFactory.select(qPartyroomData)
                .from(qPartyroomData)
                .where(
                        qPartyroomData.updatedAt.before(LocalDateTime.now(clock).minusDays(days)),
                        qPartyroomData.status.ne(PartyroomStatus.TERMINATED),
                        // #280 root-cause fix — MAIN 시스템 stage 는 cleanup 대상 아님.
                        // 호출자 deleteUnusedPartyroom 가 무가드 terminate forEach 라
                        // repo level 에서 제외하는 게 가장 깔끔 (load 작아짐 + 의도 명확).
                        qPartyroomData.stageType.ne(StageType.MAIN)
                )
                .fetch();
    }
}
