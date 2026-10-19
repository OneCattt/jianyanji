package com.jianyanji.common;

import org.springframework.web.bind.annotation.*;

/** 未定义 API 保持 JSON 404 响应，不交给前端静态资源处理器。 */
@RestController
public class UnknownApi {
    @RequestMapping("/api/**")
    public Object missing() {
        throw BusinessException.notFound("接口不存在");
    }
}
