package com.pfplaybackend.api.playlist.adapter.out.persistence;

import com.pfplaybackend.api.common.domain.value.UserId;
import com.pfplaybackend.api.playlist.adapter.out.persistence.custom.PlaylistRepositoryCustom;
import com.pfplaybackend.api.playlist.domain.entity.data.PlaylistData;
import com.pfplaybackend.api.playlist.domain.enums.PlaylistType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface PlaylistRepository extends JpaRepository<PlaylistData, Long>, PlaylistRepositoryCustom {
    List<PlaylistData> findAllByOwnerId(UserId userId);

    @Modifying
    @Query("UPDATE PlaylistData p SET p.lastPlayedTrackId = :trackId WHERE p.id = :playlistId")
    void updateLastPlayedTrackId(@Param("playlistId") Long playlistId, @Param("trackId") Long trackId);

    List<PlaylistData> findByOwnerIdAndTypeOrderByOrderNumberDesc(UserId userId, PlaylistType type);

    PlaylistData findByOwnerIdAndType(UserId userId, PlaylistType type);

    Optional<PlaylistData> findByIdAndOwnerIdAndType(Long playlistId, UserId userId, PlaylistType type);

    Optional<PlaylistData> findByIdAndOwnerId(Long playlistId, UserId userId);
}