package com.pfplaybackend.api.common.exception;

public enum ErrorType {
    BAD_REQUEST(400, "잘못된 요청"),
    UNAUTHORIZED(401, "인증 실패"),
    FORBIDDEN(403, "권한 부족 또는 접근 제한"),
    NOT_FOUND(404, "리소스를 찾을 수 없음"),
    CONFLICT(409, "리소스 충돌"),
    TOO_MANY_REQUESTS(429, "요청 한도 초과");

    private final int statusCode;
    private final String description;

    ErrorType(int statusCode, String description) {
        this.statusCode = statusCode;
        this.description = description;
    }

    public int getStatusCode() {
        return statusCode;
    }

    public String getDescription() {
        return description;
    }
}
