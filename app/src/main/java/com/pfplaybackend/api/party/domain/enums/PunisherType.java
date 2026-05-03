package com.pfplaybackend.api.party.domain.enums;

/**
 * crew_penalty_history.punisher_type 컬럼(V8) 매핑.
 * - CREW: 호스트/모더레이터(crew)가 부과
 * - ADMIN: 어드민(crew 아님)이 부과 — punisher_crew_id는 NULL, administrator_id는 partyroom_admin_action에 기록
 */
public enum PunisherType {
    CREW, ADMIN
}
