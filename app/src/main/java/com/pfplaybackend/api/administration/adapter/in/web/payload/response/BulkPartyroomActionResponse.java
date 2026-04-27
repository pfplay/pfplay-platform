package com.pfplaybackend.api.administration.adapter.in.web.payload.response;

import java.util.List;

/**
 * B-8 일괄 액션 결과. 항목별로 success/error를 노출하여 partial-failure 시 클라이언트가
 * 어떤 partyroomId가 실패했는지 식별할 수 있도록 함.
 */
public record BulkPartyroomActionResponse(List<BulkActionResult> results) {
    public record BulkActionResult(Long partyroomId, boolean success, String error) {}
}
