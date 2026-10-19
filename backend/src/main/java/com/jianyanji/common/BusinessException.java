package com.jianyanji.common;

/** 业务异常，保留原接口的 HTTP 状态、错误代码及中文提示。 */
public class BusinessException extends RuntimeException {
    /** HTTP 状态，取值为有效的 4xx 或 5xx。 */
    private final int status;
    /** 供客户端识别的稳定错误代码。 */
    private final String code;

    public BusinessException(int status, String code, String message) {
        super(message);
        this.status = status;
        this.code = code;
    }

    public int getStatus() {
        return status;
    }

    public String getCode() {
        return code;
    }

    public static BusinessException conflict(String message) {
        return new BusinessException(409, "BUSINESS_CONFLICT", message);
    }

    public static BusinessException conflict(String code, String message) {
        return new BusinessException(409, code, message);
    }

    public static BusinessException notFound(String message) {
        return new BusinessException(404, "NOT_FOUND", message);
    }

    public static BusinessException invalid(String message) {
        return new BusinessException(400, "VALIDATION_ERROR", message);
    }
}
