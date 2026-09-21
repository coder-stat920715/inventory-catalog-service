package com.publicissapient.inventory.exception;

import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.time.Instant;
import java.util.Map;

/**
 * Catches anything that escapes the cache-layer's own defenses (CacheErrorHandler,
 * Resilience4j fallback methods) so a caller always gets a clean JSON error body instead
 * of a raw stack trace, regardless of whether the root cause was Redis, the DB, or
 * application logic.
 */
@RestControllerAdvice
@Slf4j
public class GlobalExceptionHandler {

    @ExceptionHandler(org.springframework.data.redis.RedisConnectionFailureException.class)
    public ResponseEntity<Map<String, Object>> handleRedisDown(Exception ex) {
        log.error("Redis connection failure reached the controller layer (should be rare - "
                + "most paths degrade gracefully before this point): {}", ex.toString());
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .body(errorBody("CACHE_UNAVAILABLE", "Temporary degradation - please retry."));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, Object>> handleGeneric(Exception ex) {
        log.error("Unhandled exception", ex);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(errorBody("INTERNAL_ERROR", "An unexpected error occurred."));
    }

    private Map<String, Object> errorBody(String code, String message) {
        return Map.of(
                "code", code,
                "message", message,
                "timestamp", Instant.now().toString());
    }
}
