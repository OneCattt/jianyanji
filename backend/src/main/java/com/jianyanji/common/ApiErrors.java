package com.jianyanji.common;

import javax.servlet.http.HttpServletRequest;
import org.slf4j.*;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.*;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.NoHandlerFoundException;

/** API 异常统一响应，保留请求编号并避免将 SQL、凭据或堆栈返回客户端。 */
@RestControllerAdvice
public class ApiErrors {
    /** 结构化服务日志。 */
    private static final Logger LOG = LoggerFactory.getLogger(ApiErrors.class);
    /** 审计和当前请求身份访问器。 */
    private final Access access;

    public ApiErrors(Access access) {
        this.access = access;
    }

    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<Object> business(BusinessException e, HttpServletRequest request) {
        if (request.getAttribute("auditRecorded") == null
                && (request.getAttribute("staff") != null || request.getAttribute("user") != null)
                && !"GET".equals(request.getMethod())
                && !"HEAD".equals(request.getMethod())) {
            try {
                access.audit(
                        "request.denied",
                        "permission",
                        request.getMethod() + " " + request.getRequestURI(),
                        e.getMessage(),
                        "denied");
            } catch (Exception auditError) {
                LOG.error(
                        "失败审计写入失败 requestId={} path={}",
                        access.requestId(),
                        request.getRequestURI(),
                        auditError);
            }
        }
        return result(e.getStatus(), e.getCode(), e.getMessage(), request);
    }

    @ExceptionHandler({
        HttpMessageNotReadableException.class,
        MethodArgumentTypeMismatchException.class
    })
    public ResponseEntity<Object> invalid(Exception e, HttpServletRequest request) {
        if (e instanceof HttpMessageNotReadableException
                && ((HttpMessageNotReadableException) e).getMostSpecificCause()
                        instanceof com.fasterxml.jackson.core.JsonParseException) {
            return result(400, "INVALID_JSON", "请求内容格式有误", request);
        }
        return result(400, "VALIDATION_ERROR", "填写内容有误，请检查字段和格式", request);
    }

    @ExceptionHandler(NoHandlerFoundException.class)
    public ResponseEntity<Object> missing(Exception e, HttpServletRequest request) {
        return result(404, "NOT_FOUND", "接口不存在", request);
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<Object> conflict(Exception e, HttpServletRequest request) {
        LOG.warn(
                "业务约束冲突 requestId={} method={} path={}",
                request.getAttribute("requestId"),
                request.getMethod(),
                request.getRequestURI());
        return result(409, "BUSINESS_CONFLICT", "业务数据已变化或请求重复，请刷新后核对", request);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Object> unexpected(Exception e, HttpServletRequest request) {
        LOG.error(
                "请求处理失败 requestId={} method={} path={}",
                request.getAttribute("requestId"),
                request.getMethod(),
                request.getRequestURI(),
                e);
        return result(500, "INTERNAL_ERROR", "处理失败，请稍后重试", request);
    }

    private ResponseEntity<Object> result(
            int status, String code, String message, HttpServletRequest request) {
        return ResponseEntity.status(status)
                .body(
                        J.obj(
                                "code",
                                code,
                                "message",
                                message,
                                "requestId",
                                request.getAttribute("requestId")));
    }
}
