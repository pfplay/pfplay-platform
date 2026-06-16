package com.pfplaybackend.api.notification.adapter.in.web;

import com.pfplaybackend.api.common.ApiCommonResponse;
import com.pfplaybackend.api.common.ThreadLocalContext;
import com.pfplaybackend.api.notification.adapter.in.web.payload.request.PushSubscribeRequest;
import com.pfplaybackend.api.notification.adapter.in.web.payload.request.PushUnsubscribeRequest;
import com.pfplaybackend.api.notification.application.service.PushSubscriptionService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@Tag(name = "Push API")
@RequestMapping("/api/v1/push/subscriptions")
@RestController
@RequiredArgsConstructor
public class PushSubscriptionController {

    private final PushSubscriptionService service;

    @Operation(summary = "푸시 구독 등록/갱신")
    @SecurityRequirement(name = "cookieAuth")
    @PostMapping
    public ResponseEntity<ApiCommonResponse<Map<String, Long>>> subscribe(@Valid @RequestBody PushSubscribeRequest req) {
        Long userId = ThreadLocalContext.getAuthContext().getUserId().getUid();
        Long id = service.subscribe(userId, req.endpoint(), req.p256dh(), req.auth(), req.lang());
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiCommonResponse.success(Map.of("subscriptionId", id)));
    }

    @Operation(summary = "푸시 구독 해지")
    @SecurityRequirement(name = "cookieAuth")
    @DeleteMapping
    public ResponseEntity<Void> unsubscribe(@Valid @RequestBody PushUnsubscribeRequest req) {
        Long userId = ThreadLocalContext.getAuthContext().getUserId().getUid();
        service.unsubscribe(userId, req.endpoint());
        return ResponseEntity.noContent().build();
    }
}
