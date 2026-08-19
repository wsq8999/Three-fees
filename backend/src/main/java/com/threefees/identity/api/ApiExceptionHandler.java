package com.threefees.identity.api;

import com.threefees.ai.application.AiServiceException;
import com.threefees.identity.application.AuthenticationRequiredException;
import com.threefees.identity.application.BusinessRuleException;
import com.threefees.identity.application.CsrfValidationException;
import com.threefees.identity.application.InvalidCredentialsException;
import com.threefees.identity.application.ResourceConflictException;
import com.threefees.identity.application.ResourceNotFoundException;
import com.threefees.importing.application.ImportValidationException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolationException;
import java.net.URI;
import java.util.Comparator;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.util.DisconnectedClientHelper;

@RestControllerAdvice
public class ApiExceptionHandler {

  private static final Logger LOGGER = LoggerFactory.getLogger(ApiExceptionHandler.class);

  @ExceptionHandler(AuthenticationRequiredException.class)
  ResponseEntity<ProblemDetail> authenticationRequired(
      AuthenticationRequiredException exception, HttpServletRequest request) {
    return problem(
        HttpStatus.UNAUTHORIZED,
        "authentication-required",
        "AUTHENTICATION_REQUIRED",
        "需要登录",
        exception.getMessage(),
        request,
        List.of());
  }

  @ExceptionHandler(InvalidCredentialsException.class)
  ResponseEntity<ProblemDetail> invalidCredentials(
      InvalidCredentialsException exception, HttpServletRequest request) {
    return problem(
        HttpStatus.UNAUTHORIZED,
        "invalid-credentials",
        "INVALID_CREDENTIALS",
        "认证失败",
        exception.getMessage(),
        request,
        List.of());
  }

  @ExceptionHandler(CsrfValidationException.class)
  ResponseEntity<ProblemDetail> csrfValidationFailed(
      CsrfValidationException exception, HttpServletRequest request) {
    return problem(
        HttpStatus.FORBIDDEN,
        "csrf-validation-failed",
        "CSRF_VALIDATION_FAILED",
        "请求安全校验失败",
        exception.getMessage(),
        request,
        List.of());
  }

  @ExceptionHandler(AccessDeniedException.class)
  ResponseEntity<ProblemDetail> accessDenied(
      AccessDeniedException exception, HttpServletRequest request) {
    return problem(
        HttpStatus.FORBIDDEN,
        "access-denied",
        "ACCESS_DENIED",
        "无权访问",
        "当前账号无权访问该资源",
        request,
        List.of());
  }

  @ExceptionHandler(ResourceNotFoundException.class)
  ResponseEntity<ProblemDetail> resourceNotFound(
      ResourceNotFoundException exception, HttpServletRequest request) {
    return problem(
        HttpStatus.NOT_FOUND,
        "resource-not-found",
        exception.code(),
        "资源不存在",
        exception.getMessage(),
        request,
        List.of());
  }

  @ExceptionHandler(ResourceConflictException.class)
  ResponseEntity<ProblemDetail> resourceConflict(
      ResourceConflictException exception, HttpServletRequest request) {
    return problem(
        HttpStatus.CONFLICT,
        "resource-conflict",
        exception.code(),
        "资源状态冲突",
        exception.getMessage(),
        request,
        List.of());
  }

  @ExceptionHandler(BusinessRuleException.class)
  ResponseEntity<ProblemDetail> businessRuleFailed(
      BusinessRuleException exception, HttpServletRequest request) {
    return problem(
        HttpStatus.UNPROCESSABLE_ENTITY,
        "business-rule-failed",
        exception.code(),
        "业务校验失败",
        exception.getMessage(),
        request,
        List.of());
  }

  @ExceptionHandler(AiServiceException.class)
  ResponseEntity<ProblemDetail> aiServiceFailed(
      AiServiceException exception, HttpServletRequest request) {
    HttpStatus status =
        exception.retryable() ? HttpStatus.SERVICE_UNAVAILABLE : HttpStatus.UNPROCESSABLE_ENTITY;
    return problem(
        status,
        "ai-service-failed",
        exception.code(),
        "AI 稽核助手暂未完成处理",
        exception.getMessage(),
        request,
        List.of());
  }

  @ExceptionHandler(ImportValidationException.class)
  ResponseEntity<ProblemDetail> importValidationFailed(
      ImportValidationException exception, HttpServletRequest request) {
    List<FieldErrorResponse> fieldErrors =
        exception.errors().stream()
            .map(
                error ->
                    new FieldErrorResponse(
                        "row " + error.row() + " / " + error.column(),
                        error.code(),
                        error.message()))
            .toList();
    return problem(
        HttpStatus.UNPROCESSABLE_ENTITY,
        "import-validation-failed",
        "IMPORT_VALIDATION_FAILED",
        "业务校验失败",
        "导入文件存在校验错误，请查看具体行号和字段",
        request,
        fieldErrors);
  }

  @ExceptionHandler(MethodArgumentNotValidException.class)
  ResponseEntity<ProblemDetail> invalidBody(
      MethodArgumentNotValidException exception, HttpServletRequest request) {
    List<FieldErrorResponse> fieldErrors =
        exception.getBindingResult().getFieldErrors().stream()
            .map(
                error ->
                    new FieldErrorResponse(
                        error.getField(),
                        error.getCode() == null ? "Invalid" : error.getCode(),
                        error.getDefaultMessage() == null ? "字段值不正确" : error.getDefaultMessage()))
            .sorted(Comparator.comparing(FieldErrorResponse::field))
            .toList();
    return problem(
        HttpStatus.UNPROCESSABLE_ENTITY,
        "validation-failed",
        "VALIDATION_FAILED",
        "请求参数校验失败",
        "请修正标记字段后重试",
        request,
        fieldErrors);
  }

  @ExceptionHandler(ConstraintViolationException.class)
  ResponseEntity<ProblemDetail> invalidParameters(
      ConstraintViolationException exception, HttpServletRequest request) {
    List<FieldErrorResponse> fieldErrors =
        exception.getConstraintViolations().stream()
            .map(
                violation ->
                    new FieldErrorResponse(
                        violation.getPropertyPath().toString(),
                        violation
                            .getConstraintDescriptor()
                            .getAnnotation()
                            .annotationType()
                            .getSimpleName(),
                        violation.getMessage()))
            .sorted(Comparator.comparing(FieldErrorResponse::field))
            .toList();
    return problem(
        HttpStatus.UNPROCESSABLE_ENTITY,
        "validation-failed",
        "VALIDATION_FAILED",
        "请求参数校验失败",
        "请修正标记字段后重试",
        request,
        fieldErrors);
  }

  @ExceptionHandler({
    HttpMessageNotReadableException.class,
    MethodArgumentTypeMismatchException.class
  })
  ResponseEntity<ProblemDetail> malformedRequest(Exception exception, HttpServletRequest request) {
    return problem(
        HttpStatus.BAD_REQUEST,
        "malformed-request",
        "MALFORMED_REQUEST",
        "请求格式不正确",
        "无法解析请求内容",
        request,
        List.of());
  }

  @ExceptionHandler(Exception.class)
  ResponseEntity<ProblemDetail> internalError(Exception exception, HttpServletRequest request) {
    String traceId = traceId(request);

    /*
     * 浏览器刷新、跳转、主动取消请求时，Tomcat/Spring 可能抛出
     * ClientAbortException、AsyncRequestNotUsableException、
     * broken pipe、connection reset by peer 等异常。
     *
     * 这类异常代表“客户端已经断开”，不是业务处理失败。
     * 此时响应流通常已经不可写，不能再尝试返回 ProblemDetail，
     * 否则会继续触发 HttpMessageNotWritableException。
     */
    if (DisconnectedClientHelper.isClientDisconnectedException(exception)) {
      LOGGER.debug(
          "Client disconnected before response completed traceId={} exceptionType={}",
          traceId,
          exception.getClass().getName());
      return null;
    }

    LOGGER.error(
        "Unhandled request failure traceId={} exceptionType={}",
        traceId,
        exception.getClass().getName(),
        exception);

    return problem(
        HttpStatus.INTERNAL_SERVER_ERROR,
        "internal-error",
        "INTERNAL_ERROR",
        "服务暂时不可用",
        "请求处理失败，请稍后重试",
        request,
        List.of());
  }

  private ResponseEntity<ProblemDetail> problem(
      HttpStatus status,
      String problemType,
      String code,
      String title,
      String detail,
      HttpServletRequest request,
      List<FieldErrorResponse> fieldErrors) {
    ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
    problem.setType(URI.create("https://three-fees.example/problems/" + problemType));
    problem.setTitle(title);
    problem.setInstance(URI.create(request.getRequestURI()));
    problem.setProperty("code", code);
    problem.setProperty("traceId", traceId(request));
    problem.setProperty("fieldErrors", fieldErrors);

    return ResponseEntity.status(status)
        .contentType(MediaType.APPLICATION_PROBLEM_JSON)
        .body(problem);
  }

  private String traceId(HttpServletRequest request) {
    Object traceId = request.getAttribute("traceId");
    return traceId == null ? "unavailable" : traceId.toString();
  }
}
