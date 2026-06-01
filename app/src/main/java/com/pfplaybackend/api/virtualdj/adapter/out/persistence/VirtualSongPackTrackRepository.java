package com.pfplaybackend.api.virtualdj.adapter.out.persistence;

import com.pfplaybackend.api.virtualdj.domain.entity.data.VirtualSongPackTrackData;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface VirtualSongPackTrackRepository extends JpaRepository<VirtualSongPackTrackData, Long> {

    List<VirtualSongPackTrackData> findBySongPackIdOrderByOrderNumberAsc(Long songPackId);
}
