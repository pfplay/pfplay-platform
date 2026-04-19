package com.pfplaybackend.api.common.exception;

import com.pfplaybackend.api.common.ApiErrorResponse;
import com.pfplaybackend.api.common.exception.http.*;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.core.MethodParameter;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.validation.BeanPropertyBindingResult;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;

import java.lang.reflect.Method;

import static org.assertj.core.api.Assertions.assertThat;

@ExtendWith(MockitoExtension.class)
class GlobalExceptionHandlerTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    @Test
    @DisplayName("handleBadRequestException — 400 상태코드를 반환한다")
    void handleBadRequestExceptionReturns400() {
        // given
        BadRequestException ex = new BadRequestException("ERR-001", "Bad request");

        // when
        ResponseEntity<ApiErrorResponse> response = handler.handleBadRequestException(ex);

        // then
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().status()).isEqualTo(400);
        assertThat(response.getBody().errorCode()).isEqualTo("ERR-001");
        assertThat(response.getBody().message()).isEqualTo("Bad request");
    }

    @Test
    @DisplayName("handleUnauthorizedException — 401 상태코드를 반환한다")
    void handleUnauthorizedExceptionReturns401() {
        // given
        UnauthorizedException ex = new UnauthorizedException("ERR-002", "Unauthorized");

        // when
        ResponseEntity<ApiErrorResponse> response = handler.handleUnauthorizedException(ex);

        // then
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().status()).isEqualTo(401);
        assertThat(response.getBody().errorCode()).isEqualTo("ERR-002");
        assertThat(response.getBody().message()).isEqualTo("Unauthorized");
    }

    @Test
    @DisplayName("handleForbiddenException — 403 상태코드를 반환한다")
    void handleForbiddenExceptionReturns403() {
        // given
        ForbiddenException ex = new ForbiddenException("ERR-003", "Forbidden");

        // when
        ResponseEntity<ApiErrorResponse> response = handler.handleForbiddenException(ex);

        // then
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().status()).isEqualTo(403);
        assertThat(response.getBody().errorCode()).isEqualTo("ERR-003");
        assertThat(response.getBody().message()).isEqualTo("Forbidden");
    }

    @Test
    @DisplayName("handleNotFoundException — 404 상태코드를 반환한다")
    void handleNotFoundExceptionReturns404() {
        // given
        NotFoundException ex = new NotFoundException("ERR-004", "Not found");

        // when
        ResponseEntity<ApiErrorResponse> response = handler.handleNotFoundException(ex);

        // then
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().status()).isEqualTo(404);
        assertThat(response.getBody().errorCode()).isEqualTo("ERR-004");
        assertThat(response.getBody().message()).isEqualTo("Not found");
    }

    @Test
    @DisplayName("handleConflictException — 409 상태코드를 반환한다")
    void handleConflictExceptionReturns409() {
        // given
        ConflictException ex = new ConflictException("ERR-005", "Conflict");

        // when
        ResponseEntity<ApiErrorResponse> response = handler.handleConflictException(ex);

        // then
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().status()).isEqualTo(409);
        assertThat(response.getBody().errorCode()).isEqualTo("ERR-005");
        assertThat(response.getBody().message()).isEqualTo("Conflict");
    }

    @Test
    @DisplayName("handleAccessDeniedException — 403 상태코드를 반환한다")
    void handleAccessDeniedExceptionReturns403() {
        // given
        AccessDeniedException ex = new AccessDeniedException("Access denied");

        // when
        ResponseEntity<ApiErrorResponse> response = handler.handleAccessDeniedException(ex);

        // then
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().status()).isEqualTo(403);
    }

    @Test
    @DisplayName("handleValidationFailure — @Valid 실패 시 400 상태코드를 반환한다")
    void handleValidationFailureReturns400() throws NoSuchMethodException {
        // given — build a MethodArgumentNotValidException with one field error
        MethodParameter methodParameter = methodParameterForTarget();
        BeanPropertyBindingResult bindingResult = new BeanPropertyBindingResult(new Object(), "target");
        bindingResult.addError(new FieldError("target", "countryCode",
                "countryCode는 대문자 2글자여야 합니다 (ISO 3166-1 alpha-2)."));
        MethodArgumentNotValidException ex = new MethodArgumentNotValidException(methodParameter, bindingResult);

        // when
        ResponseEntity<ApiErrorResponse> response = handler.handleValidationFailure(ex);

        // then
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().status()).isEqualTo(400);
        assertThat(response.getBody().message()).contains("countryCode");
        assertThat(response.getBody().message()).contains("대문자 2글자");
    }

    @Test
    @DisplayName("handleValidationFailure — 다중 필드 에러는 메시지에 세미콜론으로 결합된다")
    void handleValidationFailureConcatenatesMultipleErrors() throws NoSuchMethodException {
        // given
        MethodParameter methodParameter = methodParameterForTarget();
        BeanPropertyBindingResult bindingResult = new BeanPropertyBindingResult(new Object(), "target");
        bindingResult.addError(new FieldError("target", "fieldA", "A must not be null"));
        bindingResult.addError(new FieldError("target", "fieldB", "B must be positive"));
        MethodArgumentNotValidException ex = new MethodArgumentNotValidException(methodParameter, bindingResult);

        // when
        ResponseEntity<ApiErrorResponse> response = handler.handleValidationFailure(ex);

        // then
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody().message())
                .contains("fieldA: A must not be null")
                .contains("fieldB: B must be positive")
                .contains(";");
    }

    private static MethodParameter methodParameterForTarget() throws NoSuchMethodException {
        Method method = GlobalExceptionHandlerTest.class.getDeclaredMethod("dummyTarget", String.class);
        return new MethodParameter(method, 0);
    }

    @SuppressWarnings("unused")
    private void dummyTarget(String arg) {
        // placeholder for MethodParameter construction in tests
    }
}
