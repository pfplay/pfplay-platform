package com.pfplaybackend.api.party.adapter.out.persistence;

import com.pfplaybackend.api.common.domain.value.UserId;
import com.pfplaybackend.api.party.domain.entity.data.CrewData;
import com.pfplaybackend.api.party.domain.value.PartyroomId;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface CrewRepository extends JpaRepository<CrewData, Long> {
    List<CrewData> findByPartyroomIdAndIsActiveTrue(PartyroomId partyroomId);
    Optional<CrewData> findByPartyroomIdAndUserId(PartyroomId partyroomId, UserId userId);
    long countByPartyroomIdAndIsActiveTrue(PartyroomId partyroomId);

    /**
     * crew row를 active로 조건부 toggle.
     *  - 반환 1: is_active=false → true 전이 발생 (호출자는 ENTER 이벤트 발행 의무)
     *  - 반환 0: row 없거나 이미 active (호출자는 후속 분기 — 새 INSERT 또는 idempotent return)
     * Race B-reentry 차단: 동시 호출 중 정확히 한 호출자만 1 반환.
     */
    @Modifying(clearAutomatically = true)
    @Query("UPDATE CrewData c " +
           "SET c.isActive = true, c.enteredAt = :now " +
           "WHERE c.partyroomId = :partyroomId AND c.userId = :userId AND c.isActive = false")
    int activateCrew(@Param("partyroomId") PartyroomId partyroomId,
                     @Param("userId") UserId userId,
                     @Param("now") LocalDateTime now);

    /**
     * crew row를 inactive로 조건부 toggle.
     *  - 반환 1: is_active=true → false 전이 발생 (호출자는 EXIT 이벤트 발행 의무)
     *  - 반환 0: row 없거나 이미 inactive (호출자 idempotent return)
     */
    @Modifying(clearAutomatically = true)
    @Query("UPDATE CrewData c " +
           "SET c.isActive = false, c.exitedAt = :now " +
           "WHERE c.partyroomId = :partyroomId AND c.userId = :userId AND c.isActive = true")
    int deactivateCrew(@Param("partyroomId") PartyroomId partyroomId,
                       @Param("userId") UserId userId,
                       @Param("now") LocalDateTime now);

    /**
     * 특정 partyroom의 모든 active crew를 일괄 inactive 전환. atomic single statement.
     * B-3 admin terminate 흐름에서 사용 — N개 crew를 1개 SQL로 처리.
     *
     *  - 반환: 영향 받은 row 수 (기존 active crew 수)
     *  - exitedAt 일괄 설정
     *  - 동시에 같은 룸에 enter 시도하는 crew는 별 race 영역
     *    (UNIQUE 제약 + B-3은 즉시 status=TERMINATED라 PartyroomEntrySpecification에서 거부됨)
     */
    @Modifying(clearAutomatically = true)
    @Query("UPDATE CrewData c SET c.isActive = false, c.exitedAt = :now " +
           "WHERE c.partyroomId = :partyroomId AND c.isActive = true")
    int bulkDeactivateByPartyroomId(@Param("partyroomId") PartyroomId partyroomId,
                                    @Param("now") LocalDateTime now);
}
