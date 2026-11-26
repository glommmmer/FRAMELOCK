package dev.videosearch.api;

import java.util.Map;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;

public class ApiError extends RuntimeException {
  public final HttpStatus status;
  public final String code;

  public ApiError(HttpStatus status, String code, String message) {
    super(message);
    this.status = status;
    this.code = code;
  }

  public static ApiError bad(String message) {
    return new ApiError(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", message);
  }

  public static ApiError missing() {
    return new ApiError(HttpStatus.NOT_FOUND, "NOT_FOUND", "资源不存在或无权访问");
  }

  public static ApiError conflict(String message) {
    return new ApiError(HttpStatus.CONFLICT, "CONFLICT", message);
  }

  @RestControllerAdvice
  public static class Handler {
    @ExceptionHandler(ApiError.class)
    ResponseEntity<?> handle(ApiError e) {
      return ResponseEntity.status(e.status)
          .body(Map.of("code", e.code, "message", e.getMessage()));
    }

    @ExceptionHandler(org.springframework.web.bind.MethodArgumentNotValidException.class)
    ResponseEntity<?> invalid() {
      return ResponseEntity.badRequest()
          .body(Map.of("code", "INVALID_REQUEST", "message", "输入参数不符合要求"));
    }

    @ExceptionHandler(org.springframework.http.converter.HttpMessageNotReadableException.class)
    ResponseEntity<?> malformed() {
      return ResponseEntity.badRequest()
          .body(Map.of("code", "INVALID_JSON", "message", "请求内容格式不正确"));
    }

    @ExceptionHandler(org.springframework.web.servlet.resource.NoResourceFoundException.class)
    ResponseEntity<?> missingResource() {
      return ResponseEntity.status(404).body(Map.of("code", "NOT_FOUND", "message", "资源不存在"));
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<?> unexpected(Exception e) {
      org.slf4j.LoggerFactory.getLogger(Handler.class)
          .error("Request failed: {}", e.getClass().getSimpleName());
      return ResponseEntity.internalServerError()
          .body(Map.of("code", "INTERNAL_ERROR", "message", "处理失败，请查看任务状态或稍后重试"));
    }
  }
}
