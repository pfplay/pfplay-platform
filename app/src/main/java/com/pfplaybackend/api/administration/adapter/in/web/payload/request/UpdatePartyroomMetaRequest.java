package com.pfplaybackend.api.administration.adapter.in.web.payload.request;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;

/**
 * B-5 룸 메타데이터 수정. 부분 수정이므로 모든 필드는 nullable.
 *
 * <p>{@link #isAtLeastOnePresent()}로 "최소 1개 필드는 변경 필요" 제약을 강제한다.
 * {@code @JsonIgnore} 덕에 직렬화/역직렬화 시 boolean 필드처럼 노출되지 않는다.
 */
public record UpdatePartyroomMetaRequest(
        @Size(max = 100) String title,
        @Size(max = 500) String introduction,
        @Min(1) @Max(60) Integer playbackTimeLimit
) {
    @JsonIgnore
    @AssertTrue(message = "최소 1개 필드는 변경 필요")
    public boolean isAtLeastOnePresent() {
        return title != null || introduction != null || playbackTimeLimit != null;
    }
}
