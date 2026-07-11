package com.pfplaybackend.api.playlist.adapter.out.persistence.impl;

import com.pfplaybackend.api.common.domain.value.UserId;
import com.pfplaybackend.api.playlist.adapter.out.persistence.custom.PlaylistRepositoryCustom;
import com.pfplaybackend.api.playlist.application.dto.PlaylistSummaryDto;
import com.pfplaybackend.api.playlist.domain.entity.data.QPlaylistData;
import com.pfplaybackend.api.playlist.domain.entity.data.QTrackData;
import com.pfplaybackend.api.playlist.domain.enums.PlaylistType;
import com.querydsl.core.types.Projections;
import com.querydsl.jpa.impl.JPAQueryFactory;
import lombok.RequiredArgsConstructor;

import java.util.List;

@RequiredArgsConstructor
public class PlaylistRepositoryImpl implements PlaylistRepositoryCustom {

    private final JPAQueryFactory queryFactory;

    @Override
    public List<PlaylistSummaryDto> findAllByUserId(UserId ownerId) {
        QPlaylistData qPlaylistData = QPlaylistData.playlistData;
        QTrackData qTrackData = QTrackData.trackData;
        return queryFactory
                .select(Projections.constructor(PlaylistSummaryDto.class,
                                qPlaylistData.id,
                                qPlaylistData.name,
                                qPlaylistData.orderNumber,
                                qPlaylistData.type,
                                qTrackData.id.count().as("musicCount")
                        )
                )
                .from(qPlaylistData)
                .leftJoin(qTrackData).on(qPlaylistData.id.eq(qTrackData.playlistId.id))
                .where(qPlaylistData.ownerId.eq(ownerId)
                        .and(qPlaylistData.type.ne(PlaylistType.TEMP)))
                .groupBy(qPlaylistData.id)
                .orderBy(qPlaylistData.type.desc(), qPlaylistData.orderNumber.asc())
                .fetch();
    }

    @Override
    public PlaylistSummaryDto findByIdAndUserId(Long playlistId, UserId ownerId) {
        QPlaylistData qPlaylistData = QPlaylistData.playlistData;
        QTrackData qTrackData = QTrackData.trackData;
        return queryFactory
                .select(Projections.constructor(PlaylistSummaryDto.class,
                                qPlaylistData.id,
                                qPlaylistData.name,
                                qPlaylistData.orderNumber,
                                qPlaylistData.type,
                                qTrackData.id.count().as("musicCount")
                        )
                )
                .from(qPlaylistData)
                .leftJoin(qTrackData).on(qPlaylistData.id.eq(qTrackData.playlistId.id))
                .where(qPlaylistData.id.eq(playlistId)
                        .and(qPlaylistData.ownerId.eq(ownerId))
                        .and(qPlaylistData.type.ne(PlaylistType.TEMP))
                )
                .groupBy(qPlaylistData.id)
                .orderBy(qPlaylistData.type.desc(), qPlaylistData.orderNumber.asc())
                .fetchOne();
    }

    @Override
    public Long deleteByListIds(List<Long> listIds) {
        QPlaylistData qPlaylistData = QPlaylistData.playlistData;

        return queryFactory
                .delete(qPlaylistData)
                .where(qPlaylistData.id.in(listIds))
                .execute();
    }
}
