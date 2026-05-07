package com.pfplaybackend.api.administration.domain.port;

import com.pfplaybackend.api.administration.domain.entity.data.SystemAnnouncementData;
import com.pfplaybackend.api.administration.domain.value.MaintenancePhase;

public interface EdgeConfigPort {
    /**
     * @param entity null = end-of-maintenance (Edge Config maintenance key set to null).
     *               non-null + phase: upsert with that data.
     * Throws RuntimeException on failure — listener swallows + ERROR log. No retry.
     */
    void writeMaintenance(SystemAnnouncementData entity, MaintenancePhase phase);
}
