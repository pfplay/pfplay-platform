package com.pfplaybackend.api.party.adapter.in.web.payload.request.management;

import com.pfplaybackend.api.party.domain.entity.data.PartyroomData;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@AllArgsConstructor
@NoArgsConstructor
public class UpdateNoticeRequest {
    @NotNull(message = "content is required.")
    @Size(max = PartyroomData.MAX_NOTICE_CONTENT_LENGTH,
            message = "content must be 255 characters or less.")
    private String content;
}
