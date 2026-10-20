package com.jianyanji.content;

import static com.jianyanji.common.J.l;

import com.fasterxml.jackson.databind.JsonNode;
import com.jianyanji.common.Access;
import org.springframework.web.bind.annotation.*;

/** 课程成长权益公开对比、本人权益和后台维护的权限边界。 */
@RestController
@RequestMapping("/api")
public class CourseRightsController {
    /** 权益配置与服务端身份选择。 */
    private final CourseRightsService service;
    /** 登录与内容管理权限。 */
    private final Access access;

    public CourseRightsController(CourseRightsService service, Access access) {
        this.service = service;
        this.access = access;
    }

    @GetMapping("/public/course-rights")
    public Object overview() {
        return service.overview();
    }

    @GetMapping("/course-rights")
    public Object mine() {
        return service.mine(access.user());
    }

    @GetMapping("/admin/course-rights")
    public Object admin() {
        access.staff("content:manage");
        return service.admin();
    }

    @PutMapping("/admin/course-rights")
    public Object save(@RequestBody JsonNode body) {
        return service.save(body, l(access.staff("content:manage"), "id"));
    }
}
