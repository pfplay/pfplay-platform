package com.pfplaybackend.api.virtualdj.adapter.out.persistence;

import com.pfplaybackend.api.virtualdj.domain.entity.data.VirtualSongPackData;
import org.springframework.data.jpa.repository.JpaRepository;

public interface VirtualSongPackRepository extends JpaRepository<VirtualSongPackData, Long> {

    boolean existsByName(String name);
}
