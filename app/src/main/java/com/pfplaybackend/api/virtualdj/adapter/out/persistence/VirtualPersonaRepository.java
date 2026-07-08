package com.pfplaybackend.api.virtualdj.adapter.out.persistence;

import com.pfplaybackend.api.virtualdj.domain.entity.data.VirtualPersonaData;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface VirtualPersonaRepository extends JpaRepository<VirtualPersonaData, Long> {

    boolean existsByName(String name);

    boolean existsByNameAndIdNot(String name, Long id);

    /**
     * 활성 페르소나 전체(신규 매핑 가능한 후보). {@code VirtualPersonaData#active} 프로퍼티(컬럼 {@code is_active}) 기준.
     */
    List<VirtualPersonaData> findByActiveTrue();
}
