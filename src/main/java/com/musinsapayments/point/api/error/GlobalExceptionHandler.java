package com.musinsapayments.point.api.error;

import com.musinsapayments.point.domain.exception.PointErrorCode;
import com.musinsapayments.point.domain.exception.PointException;
import jakarta.persistence.LockTimeoutException;
import jakarta.validation.ConstraintViolationException;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

@RestControllerAdvice
public class GlobalExceptionHandler {

    private final Clock clock;

    public GlobalExceptionHandler(Clock clock) {
        this.clock = clock;
    }

    @ExceptionHandler(PointException.class)
    public ResponseEntity<PointErrorResponse> handlePoint(PointException exception) {
        HttpStatus status = switch (exception.getErrorCode()) {
            case INVALID_REQUEST -> HttpStatus.BAD_REQUEST;
            case POLICY_NOT_FOUND, POINT_NOT_FOUND -> HttpStatus.NOT_FOUND;
            case REQUEST_ID_CONFLICT, ORDER_NUMBER_CONFLICT,
                 ACCRUAL_CANCEL_NOT_ALLOWED, USE_CANCEL_AMOUNT_EXCEEDED -> HttpStatus.CONFLICT;
            case ACCRUAL_AMOUNT_LIMIT_EXCEEDED, HOLDING_LIMIT_EXCEEDED,
                 POINT_BALANCE_INSUFFICIENT -> HttpStatus.UNPROCESSABLE_ENTITY;
            case LOCK_TIMEOUT -> HttpStatus.SERVICE_UNAVAILABLE;
            case DATA_INTEGRITY_VIOLATION, INTERNAL_ERROR -> HttpStatus.INTERNAL_SERVER_ERROR;
        };
        return ResponseEntity.status(status).body(error(exception.getErrorCode(), List.of()));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<PointErrorResponse> handleValidation(MethodArgumentNotValidException exception) {
        List<PointErrorResponse.FieldErrorItem> fields = exception.getBindingResult().getFieldErrors()
                .stream()
                .map(error -> new PointErrorResponse.FieldErrorItem(
                        error.getField(), error.getDefaultMessage()))
                .toList();
        return ResponseEntity.badRequest().body(error(PointErrorCode.INVALID_REQUEST, fields));
    }

    @ExceptionHandler({
            MethodArgumentTypeMismatchException.class,
            ConstraintViolationException.class,
            HttpMessageNotReadableException.class,
            IllegalArgumentException.class
    })
    public ResponseEntity<PointErrorResponse> handleInvalidRequest(Exception exception) {
        return ResponseEntity.badRequest().body(error(PointErrorCode.INVALID_REQUEST, List.of()));
    }

    @ExceptionHandler({
            CannotAcquireLockException.class,
            PessimisticLockingFailureException.class,
            LockTimeoutException.class
    })
    public ResponseEntity<PointErrorResponse> handleLock(Exception exception) {
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .body(error(PointErrorCode.LOCK_TIMEOUT, List.of()));
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<PointErrorResponse> handleIntegrity(DataIntegrityViolationException exception) {
        String message = Optional.ofNullable(exception.getMostSpecificCause().getMessage())
                .orElse("")
                .toLowerCase(Locale.ROOT);
        if (message.contains("uk_point_ledger_request_id")) {
            return conflict(PointErrorCode.REQUEST_ID_CONFLICT);
        }
        if (message.contains("uk_point_ledger_order_number")) {
            return conflict(PointErrorCode.ORDER_NUMBER_CONFLICT);
        }
        return ResponseEntity.internalServerError()
                .body(error(PointErrorCode.DATA_INTEGRITY_VIOLATION, List.of()));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<PointErrorResponse> handleUnexpected(Exception exception) {
        return ResponseEntity.internalServerError()
                .body(error(PointErrorCode.INTERNAL_ERROR, List.of()));
    }

    private ResponseEntity<PointErrorResponse> conflict(PointErrorCode errorCode) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(error(errorCode, List.of()));
    }

    private PointErrorResponse error(
            PointErrorCode errorCode, List<PointErrorResponse.FieldErrorItem> fieldErrors) {
        return new PointErrorResponse(
                OffsetDateTime.now(clock), errorCode.name(), errorCode.message(), fieldErrors);
    }
}
