package com.pfplaybackend.api.virtualdj.adapter.out.persistence;

import com.pfplaybackend.api.virtualdj.domain.entity.data.VirtualPersonaData;
import org.springframework.data.jpa.repository.JpaRepository;

public interface VirtualPersonaRepository extends JpaRepository<VirtualPersonaData, Long> {

    boolean existsByName(String name);

    boolean existsByNameAndIdNot(String name, Long id);
}
