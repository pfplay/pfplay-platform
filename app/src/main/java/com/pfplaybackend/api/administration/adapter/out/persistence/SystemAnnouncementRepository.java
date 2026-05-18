package com.pfplaybackend.api.administration.adapter.out.persistence;

import com.pfplaybackend.api.administration.domain.entity.data.SystemAnnouncementData;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface SystemAnnouncementRepository extends JpaRepository<SystemAnnouncementData, Long> {

    @Query("""
        SELECT a FROM SystemAnnouncementData a
        WHERE a.type = com.pfplaybackend.api.administration.domain.value.AnnouncementType.MAINTENANCE_NOTICE
          AND a.maintenanceStartedAt IS NULL AND a.cancelledAt IS NULL
          AND a.scheduledStartAt <= :now AND a.scheduledEndAt > :now
        """)
    List<SystemAnnouncementData> findDueForMaintenanceActivation(@Param("now") LocalDateTime now);

    @Query("""
        SELECT a FROM SystemAnnouncementData a
        WHERE a.type IN (com.pfplaybackend.api.administration.domain.value.AnnouncementType.EVENT,
                         com.pfplaybackend.api.administration.domain.value.AnnouncementType.EMERGENCY)
          AND a.cancelledAt IS NULL
          AND (a.expiresAt IS NULL OR a.expiresAt > :now)
        ORDER BY a.sentAt DESC
        """)
    List<SystemAnnouncementData> findActivePublic(@Param("now") LocalDateTime now);

    @Query("""
        SELECT a FROM SystemAnnouncementData a
        WHERE a.type = com.pfplaybackend.api.administration.domain.value.AnnouncementType.MAINTENANCE_NOTICE
          AND a.maintenanceStartedAt IS NOT NULL AND a.cancelledAt IS NULL AND a.completedAt IS NULL
        """)
    Optional<SystemAnnouncementData> findCurrentMaintenance();

    @Query("""
        SELECT a FROM SystemAnnouncementData a
        WHERE a.type = com.pfplaybackend.api.administration.domain.value.AnnouncementType.MAINTENANCE_NOTICE
          AND a.maintenanceStartedAt IS NOT NULL
          AND a.cancelledAt IS NULL AND a.completedAt IS NULL
          AND a.scheduledEndAt <= :now
        """)
    List<SystemAnnouncementData> findDueForMaintenanceCompletion(@Param("now") LocalDateTime now);

    @Query("""
        SELECT a FROM SystemAnnouncementData a
        WHERE a.type = com.pfplaybackend.api.administration.domain.value.AnnouncementType.MAINTENANCE_NOTICE
          AND a.maintenanceStartedAt IS NULL AND a.cancelledAt IS NULL AND a.scheduledStartAt > :now
        ORDER BY a.scheduledStartAt ASC
        """)
    List<SystemAnnouncementData> findPlannedMaintenance(@Param("now") LocalDateTime now);

    Page<SystemAnnouncementData> findAll(Pageable pageable);
}
