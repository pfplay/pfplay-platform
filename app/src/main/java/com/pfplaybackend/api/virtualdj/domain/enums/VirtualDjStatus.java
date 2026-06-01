package com.pfplaybackend.api.virtualdj.domain.enums;

public enum VirtualDjStatus {
    /** 가상 DJ 비활성화 */
    OFF,
    /** 가상 DJ 활성화 — 봇 수 자동 관리 */
    MANAGED,
    /** 가상 DJ 동결 — 현재 상태 유지 (봇 수 변경 금지) */
    FROZEN
}
