package com.jianyanji.common;

import java.io.IOException;
import java.net.URI;
import java.util.Arrays;
import javax.servlet.*;
import javax.servlet.http.*;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/** 请求追踪及后台同源写入检查，不信任客户端自报的用户身份和来源 IP。 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class ApiFilter extends OncePerRequestFilter {
    @Override
    protected void doFilterInternal(
            HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String requestId = J.id();
        request.setAttribute("requestId", requestId);
        MDC.put("requestId", requestId);
        response.setHeader("X-Request-ID", requestId);
        response.setHeader("X-Content-Type-Options", "nosniff");
        // 跨站图片只携带站点来源，供 OSS 防盗链匹配，不泄露页面路径和查询参数。
        response.setHeader("Referrer-Policy", "strict-origin-when-cross-origin");
        response.setHeader("X-Frame-Options", "DENY");
        if (request.getRequestURI().startsWith("/api/"))
            response.setHeader("Cache-Control", "no-store");
        try {
            if (request.getContentLengthLong() > 131072)
                throw new BusinessException(413, "BODY_TOO_LARGE", "请求内容超过允许大小");
            if (request.getRequestURI().startsWith("/api/admin")
                    && !Arrays.asList("GET", "HEAD", "OPTIONS").contains(request.getMethod())) {
                boolean origin = false;
                try {
                    URI uri = URI.create(request.getHeader("Origin"));
                    origin =
                            ("https".equals(uri.getScheme()) || "http".equals(uri.getScheme()))
                                    && request.getHeader("Host").equals(uri.getRawAuthority());
                } catch (Exception ignored) {
                }
                if (!origin || "cross-site".equals(request.getHeader("Sec-Fetch-Site")))
                    throw new BusinessException(403, "ORIGIN_DENIED", "请从管理后台页面操作");
            }
            chain.doFilter(request, response);
        } catch (BusinessException e) {
            response.setStatus(e.getStatus());
            response.setContentType("application/json;charset=UTF-8");
            response.getWriter()
                    .write(
                            J.stringify(
                                    J.obj(
                                            "code",
                                            e.getCode(),
                                            "message",
                                            e.getMessage(),
                                            "requestId",
                                            requestId)));
        } finally {
            MDC.remove("requestId");
        }
    }
}
