package com.pfplaybackend.api.operations.application.port.out;

/**
 * 점검 모드 여부를 판정하는 outbound 포트.
 *
 * <p>구현(어댑터)은 <b>운영자가 실제로 토글하는</b> system_announcement 의 현재 ACTIVE 점검을
 * 단일 진실원천으로 삼는다. 과거에는 {@code MaintenanceModeFilter} 가 system_config 의
 * {@code maintenance.enabled} 키를 봤으나 그 키를 write 하는 코드가 없어 백엔드 점검 게이트가
 * 영원히 열려 있었다(issue #267). 이 포트로 소스를 일원화한다.
 */
public interface MaintenanceGate {

    /** 현재 점검 중인가. */
    boolean isUnderMaintenance();

    /** 점검 중일 때 503 본문에 실을 메시지. */
    String getMaintenanceMessage();
}
