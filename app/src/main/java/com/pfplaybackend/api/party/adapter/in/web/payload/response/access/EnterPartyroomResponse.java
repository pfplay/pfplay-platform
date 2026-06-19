package com.pfplaybackend.api.party.adapter.in.web.payload.response.access;


import com.pfplaybackend.api.party.domain.entity.data.CrewData;
import com.pfplaybackend.api.party.domain.enums.GradeType;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.Getter;

@Getter
@Data
@AllArgsConstructor
public class EnterPartyroomResponse {
    private long crewId;
    private GradeType gradeType;
    /** WS 재연결 resync 신호: 멤버십이 inactive→active 로 재활성됐는지 (web#402). */
    private boolean reactivated;

    public static EnterPartyroomResponse from(CrewData crew, boolean reactivated) {
        return new EnterPartyroomResponse(crew.getId(), crew.getGradeType(), reactivated);
    }
}