package com.pfplaybackend.api.administration.adapter.in.web.payload.response;

import java.util.List;

/**
 * B-8 일괄 액션 결과. 항목별로 success/error를 노출하여 partial-failure 시 클라이언트가
 * 어떤 partyroomId가 실패했는지 식별할 수 있도록 함.
 *
 * <p>{@code errorCode}는 14c §7.1 errorCode 매트릭스(예: "PRT-001", "MBR-002")를 bulk
 * per-item 결과에도 적용 가능하도록 노출 (admin-backend-asks.md A5). success=true 시 null.
 */
public record BulkPartyroomActionResponse(List<BulkActionResult> results) {
    public record BulkActionResult(Long partyroomId, boolean success, String error, String errorCode) {}
}
