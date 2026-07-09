package com.pfplaybackend.api.administration.adapter.out.persistence;

import com.pfplaybackend.api.administration.domain.entity.data.AdministratorData;
import com.pfplaybackend.api.administration.domain.entity.data.SystemAnnouncementData;
import com.pfplaybackend.api.administration.domain.value.AnnouncementSeverity;
import com.pfplaybackend.api.administration.domain.value.AnnouncementType;
import com.pfplaybackend.api.common.AbstractIntegrationTest;
import com.pfplaybackend.api.common.domain.value.UserId;
import com.pfplaybackend.api.user.adapter.out.persistence.UserAccountRepository;
import com.pfplaybackend.api.user.domain.entity.data.UserAccountData;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@Transactional
class SystemAnnouncementRepositoryIntegrationTest extends AbstractIntegrationTest {

    private static final long ADMIN_USER_ID = 990101L;

    @Autowired
    SystemAnnouncementRepository repo;
    @Autowired
    AdministratorRepository administratorRepository;
    @Autowired
    UserAccountRepository userAccountRepository;

    private final LocalDateTime now = LocalDateTime.of(2026, 5, 4, 0, 0);

    /** FK fk_announcement_sent_by / cancelled_by → administrator(administrator_id). */
    private Long adminId;

    @BeforeEach
    void seedAdmin() {
        userAccountRepository.saveAndFlush(
                UserAccountData.createForLocal(new UserId(ADMIN_USER_ID), "sysann-repo-it@x", "h"));
        adminId = administratorRepository
                .saveAndFlush(AdministratorData.createSuperAdmin(ADMIN_USER_ID))
                .getAdministratorId();
    }

    @Test
    @DisplayName("findDueForMaintenanceActivation — start<=now, end>now, started=null, cancelled=null")
    void due() {
        repo.save(maintenance(now.minusMinutes(5), now.plusMinutes(55), null, null));   // match
        repo.save(maintenance(now.plusMinutes(5), now.plusMinutes(65), null, null));    // future start
        repo.save(maintenance(now.minusMinutes(5), now.plusMinutes(55), now.minusMinutes(1), null));   // started
        repo.save(maintenance(now.minusMinutes(5), now.plusMinutes(55), null, now.minusMinutes(1)));   // cancelled
        repo.save(event());                                                                              // wrong type
        flushAndClear();

        assertThat(repo.findDueForMaintenanceActivation(now)).hasSize(1);
    }

    @Test
    @DisplayName("findActivePublic — type IN (EVENT,EMERGENCY), cancelled=null, expiresAt null OR > now")
    void activePublic() {
        repo.save(event());
        repo.save(eventWithExpiry(now.plusMinutes(10)));
        repo.save(eventWithExpiry(now.minusMinutes(1)));   // expired
        repo.save(eventCancelled());
        repo.save(maintenance(now.plusMinutes(5), now.plusMinutes(65), null, null));   // wrong type
        flushAndClear();

        assertThat(repo.findActivePublic(now)).hasSize(2);
    }

    @Test
    @DisplayName("findCurrentMaintenance — phase ACTIVE single row")
    void current() {
        repo.save(maintenance(now.minusMinutes(5), now.plusMinutes(55), now.minusMinutes(1), null));
        flushAndClear();

        assertThat(repo.findCurrentMaintenance()).isPresent();
    }

    @Test
    @DisplayName("findPlannedMaintenance — start>now, started=null, cancelled=null, ASC")
    void planned() {
        repo.save(maintenance(now.plusMinutes(15), now.plusMinutes(75), null, null));
        repo.save(maintenance(now.plusMinutes(5), now.plusMinutes(65), null, null));
        flushAndClear();

        List<SystemAnnouncementData> p = repo.findPlannedMaintenance(now);
        assertThat(p).hasSize(2);
        assertThat(p.get(0).getScheduledStartAt()).isBefore(p.get(1).getScheduledStartAt());
    }

    private SystemAnnouncementData maintenance(LocalDateTime s, LocalDateTime e,
                                                LocalDateTime started, LocalDateTime cancelled) {
        SystemAnnouncementData d = SystemAnnouncementData.create(
                AnnouncementType.MAINTENANCE_NOTICE, AnnouncementSeverity.WARN,
                "k", "e", "ko", "en", s, e, null, now, adminId, false);
        if (started != null) ReflectionTestUtils.setField(d, "maintenanceStartedAt", started);
        if (cancelled != null) {
            ReflectionTestUtils.setField(d, "cancelledAt", cancelled);
            ReflectionTestUtils.setField(d, "cancelledByAdministratorId", adminId);
        }
        return d;
    }

    private SystemAnnouncementData event() {
        return SystemAnnouncementData.create(AnnouncementType.EVENT, AnnouncementSeverity.INFO,
                "k", "e", "ko", "en", null, null, null, now, adminId, false);
    }

    private SystemAnnouncementData eventWithExpiry(LocalDateTime exp) {
        return SystemAnnouncementData.create(AnnouncementType.EVENT, AnnouncementSeverity.INFO,
                "k", "e", "ko", "en", null, null, exp, now, adminId, false);
    }

    private SystemAnnouncementData eventCancelled() {
        SystemAnnouncementData d = event();
        d.cancel(adminId, Clock.fixed(now.toInstant(ZoneOffset.UTC), ZoneOffset.UTC));
        return d;
    }
}
