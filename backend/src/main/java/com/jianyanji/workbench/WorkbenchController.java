package com.jianyanji.workbench;

import com.jianyanji.common.Access;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 后台工作台只读待办入口；权限和计数均由服务端判定。 */
@RestController
@RequestMapping("/api/admin/workbench")
public class WorkbenchController {
    /** 待办聚合服务。 */
    private final WorkbenchService workbench;
    /** 当前工作人员会话与权限。 */
    private final Access access;

    public WorkbenchController(WorkbenchService workbench, Access access) {
        this.workbench = workbench;
        this.access = access;
    }

    /**
     * 返回当前账号有权处理的真实待办计数；无业务权限的工作人员返回空列表，匿名或过期会话返回 401。
     */
    @GetMapping("/todos")
    public Object todos() {
        return workbench.todos(access.staff(null));
    }
}
