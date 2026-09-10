package io.contentpublisher.platform.web.error;

import io.contentpublisher.platform.application.ApplicationException;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.time.Instant;
import java.util.List;

@RestControllerAdvice
public class GlobalExceptionHandler {
    @ExceptionHandler(ApplicationException.class)
    ResponseEntity<ApiError> applicationError(ApplicationException exception, HttpServletRequest request) {
        HttpStatus status = switch (exception.code()) {
            case "PROJECT_NOT_FOUND", "SNAPSHOT_NOT_FOUND", "JOB_NOT_FOUND", "ARTICLE_NOT_FOUND",
                    "ARTICLE_VERSION_NOT_FOUND", "ARTICLE_ASSET_NOT_FOUND", "CHANNEL_ACCOUNT_NOT_FOUND",
                    "PUBLICATION_NOT_FOUND",
                    "DELETED_ARTICLE_NOT_FOUND", "DELETED_JOB_NOT_FOUND", "PRESET_NOT_FOUND",
                    "NOTIFICATION_NOT_FOUND", "NOTIFICATION_ENDPOINT_NOT_FOUND" -> HttpStatus.NOT_FOUND;
            case "AI_DISABLED", "PROJECT_NOT_READY", "ARTICLE_STATE_CONFLICT", "ARTICLE_VERSION_CONFLICT",
                    "ARTICLE_NOT_APPROVED", "ARTICLE_ASSET_LIMIT",
                    "CHANNEL_ACCOUNT_DISABLED", "CHANNEL_ACCOUNT_VERSION_CONFLICT",
                    "CHANNEL_CONNECTION_NOT_READY", "CHANNEL_CONFIGURATION_UNAVAILABLE",
                    "CHANNEL_VERIFICATION_UNAVAILABLE", "PUBLICATION_ALREADY_ATTEMPTED", "CHANNELS_DISABLED",
                    "AI_SETTINGS_VERSION_CONFLICT", "ARTICLE_DELETE_ACTIVE_JOB", "JOB_DELETE_ACTIVE",
                    "JOB_NOT_CANCELLABLE", "JOB_NOT_RETRYABLE", "JOB_NOT_REPLAYABLE", "DRAFT_VERSION_CONFLICT",
                    "JOB_REPLAY_REQUIRES_MANUAL_PUBLICATION_RETRY" -> HttpStatus.CONFLICT;
            case "IDEMPOTENCY_KEY_CONFLICT" -> HttpStatus.CONFLICT;
            case "TENANT_JOB_QUOTA_EXCEEDED" -> HttpStatus.TOO_MANY_REQUESTS;
            case "ARTICLE_ASSET_TOO_LARGE" -> HttpStatus.PAYLOAD_TOO_LARGE;
            case "IDEMPOTENCY_KEY_INVALID", "INVALID_ARGUMENT", "CONTENT_FORMAT_INVALID", "ARTICLE_ASSET_INVALID",
                    "CHANNEL_CREDENTIALS_INVALID",
                    "CHANNEL_ENCRYPTION_KEY_INVALID", "DRAFT_VERSION_INVALID", "PRESET_LENGTH_INVALID",
                    "PRESET_SOURCE_INVALID", "NOTIFICATION_SEVERITY_INVALID", "CALENDAR_RANGE_INVALID"
                    -> HttpStatus.BAD_REQUEST;
            case "AI_SETTINGS_INVALID", "SECRET_ENCRYPTION_KEY_INVALID" -> HttpStatus.BAD_REQUEST;
            case "GIT_URL_INVALID", "GIT_URL_REJECTED", "GIT_HOST_REJECTED", "GIT_ADDRESS_REJECTED",
                    "GIT_REPOSITORY_TOO_LARGE", "AI_OUTPUT_REJECTED" -> HttpStatus.UNPROCESSABLE_ENTITY;
            case "AI_ENDPOINT_INVALID", "AI_ENDPOINT_REJECTED", "AI_HOST_REJECTED", "AI_ADDRESS_REJECTED"
                    -> HttpStatus.UNPROCESSABLE_ENTITY;
            case "CHANNEL_ENDPOINT_INVALID", "CHANNEL_ENDPOINT_REJECTED", "CHANNEL_HOST_REJECTED",
                    "CHANNEL_ADDRESS_REJECTED", "CANONICAL_URL_REJECTED", "PUBLISHED_URL_REJECTED",
                    "SCHEDULED_AT_INVALID", "ARTICLE_TRANSLATION_MISSING", "MANUAL_PUBLISH_UNAVAILABLE",
                    "CHANNEL_MANUAL_ONLY", "CHANNEL_NOT_SUPPORTED" -> HttpStatus.UNPROCESSABLE_ENTITY;
            case "WEBSITE_URL_INVALID", "WEBSITE_URL_REJECTED", "WEBSITE_ADDRESS_REJECTED",
                    "WEBSITE_CONTENT_REJECTED", "WEBSITE_RESPONSE_TOO_LARGE" -> HttpStatus.UNPROCESSABLE_ENTITY;
            case "WEBHOOK_URL_REJECTED" -> HttpStatus.UNPROCESSABLE_ENTITY;
            default -> HttpStatus.BAD_GATEWAY;
        };
        return error(status, exception.code(), exception.getMessage(), request, List.of());
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<ApiError> validationError(MethodArgumentNotValidException exception, HttpServletRequest request) {
        List<ApiError.FieldViolation> violations = exception.getBindingResult().getFieldErrors().stream()
                .map(error -> new ApiError.FieldViolation(error.getField(), error.getDefaultMessage())).toList();
        return error(HttpStatus.BAD_REQUEST, "REQUEST_VALIDATION_FAILED", "请求参数校验失败", request, violations);
    }

    @ExceptionHandler(IllegalArgumentException.class)
    ResponseEntity<ApiError> illegalArgument(IllegalArgumentException exception, HttpServletRequest request) {
        return error(HttpStatus.BAD_REQUEST, "INVALID_ARGUMENT", exception.getMessage(), request, List.of());
    }

    @ExceptionHandler(MissingRequestHeaderException.class)
    ResponseEntity<ApiError> missingHeader(MissingRequestHeaderException exception, HttpServletRequest request) {
        return error(HttpStatus.BAD_REQUEST, "REQUEST_HEADER_MISSING",
                "缺少请求头: " + exception.getHeaderName(), request, List.of());
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    ResponseEntity<ApiError> dataConflict(DataIntegrityViolationException exception, HttpServletRequest request) {
        return error(HttpStatus.CONFLICT, "CONCURRENT_REQUEST_CONFLICT",
                "请求与并发写入冲突，请使用原幂等键查询或稍后重试", request, List.of());
    }

    private ResponseEntity<ApiError> error(HttpStatus status, String code, String message,
                                           HttpServletRequest request, List<ApiError.FieldViolation> violations) {
        return ResponseEntity.status(status).body(new ApiError(Instant.now(), status.value(), code, message,
                request.getRequestURI(), MDC.get("traceId"), violations));
    }
}
