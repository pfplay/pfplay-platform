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
import com.pfplaybackend.api.party.domain.value.PartyroomId;
import com.querydsl.core.Tuple;
import com.querydsl.core.types.ConstructorExpression;
import com.querydsl.core.types.Projections;
import com.querydsl.jpa.JPAExpressions;
import com.querydsl.jpa.JPQLQuery;
import com.querydsl.jpa.impl.JPAQueryFactory;
import lombok.RequiredArgsConstructor;

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
        QPartyroomData qPartyroomData = QPartyroomData.partyroomData;
        QCrewData qCrewData = QCrewData.crewData;
        QPartyroomPlaybackData qPlayback = QPartyroomPlaybackData.partyroomPlaybackData;
        QDjQueueData qDjQueue = QDjQueueData.djQueueData;

        ActivePartyroomDto activePartyroomDto = queryFactory
                .select(Projections.constructor(
                        ActivePartyroomDto.class,
                        qPartyroomData.id,
                        qDjQueue.isClosed,
                        qCrewData.id.as("crewId"),
                        qPlayback.isActivated,
                        qPlayback.currentPlaybackId,
                        qPlayback.currentDjCrewId
                ))
                .from(qCrewData)
                .join(qPartyroomData).on(qPartyroomData.id.eq(qCrewData.partyroomId.id))
                .join(qPlayback).on(qPlayback.partyroomId.id.eq(qPartyroomData.id))
                .join(qDjQueue).on(qDjQueue.partyroomId.id.eq(qPartyroomData.id))
                .where(qCrewData.userId.eq(userId)
                        .and(qCrewData.isActive.eq(true)))
                .fetchOne();

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
                        qCrewData.gradeType
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
    public List<PartyroomData> findAllUnusedPartyroomDataByDay(int days) {
        QPartyroomData qPartyroomData = QPartyroomData.partyroomData;

        return queryFactory.select(qPartyroomData)
                .from(qPartyroomData)
                .where(
                        qPartyroomData.updatedAt.before(LocalDateTime.now(clock).minusDays(days)),
                        qPartyroomData.status.ne(PartyroomStatus.TERMINATED)
                )
                .fetch();
    }
}
