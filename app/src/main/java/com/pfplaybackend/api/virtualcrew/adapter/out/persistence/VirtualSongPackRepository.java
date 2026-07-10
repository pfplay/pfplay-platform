package com.pfplaybackend.api.virtualcrew.adapter.out.persistence;

import com.pfplaybackend.api.virtualcrew.domain.entity.data.VirtualSongPackData;
import org.springframework.data.jpa.repository.JpaRepository;

public interface VirtualSongPackRepository extends JpaRepository<VirtualSongPackData, Long> {

    boolean existsByName(String name);
}
