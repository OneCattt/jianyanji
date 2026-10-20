package com.jianyanji.content;

import static com.jianyanji.common.J.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jianyanji.common.*;
import org.springframework.stereotype.Service;

/** 分身份成长权益介绍及联系指引，配置复用settings，不创建报名或学习记录。 */
@Service
public class CourseRightsService {
    /** 已有配置持久化与事务。 */
    private final Db db;
    /** 配置修改审计。 */
    private final Access access;

    public CourseRightsService(Db db, Access access) {
        this.db = db;
        this.access = access;
    }

    /** 公共权益对比，只展示启用且有实际配置的条目。 */
    public ObjectNode overview() {
        JsonNode value = db.setting("course_rights");
        ObjectNode visible = obj();
        for (String identity : new String[] {"D", "C", "B"})
            if (value != null && b(value.path(identity), "enabled"))
                visible.set(identity, value.path(identity));
        return obj("configured", visible.size() > 0, "rights", visible);
    }

    /** 使用服务端有效身份决定当前权益，不接受客户端指定身份。 */
    public ObjectNode mine(ObjectNode user) {
        String identity = effectiveIdentity(user);
        JsonNode value = db.setting("course_rights");
        JsonNode rights = value == null ? null : value.get(identity);
        boolean configured = rights != null && b(rights, "enabled");
        return obj(
                "identity",
                identity,
                "configured",
                configured,
                "rights",
                configured ? rights : null);
    }

    public JsonNode admin() {
        JsonNode value = db.setting("course_rights");
        return value == null ? obj() : value;
    }

    /** 各身份分别配置权益文字、联系入口和报名指引，不支持付费或在线学习。 */
    public JsonNode save(JsonNode body, long actor) {
        Input.strict(body, "D", "C", "B", "reason");
        String reason = Input.reason(body);
        ObjectNode value = obj();
        for (String identity : new String[] {"D", "C", "B"}) {
            JsonNode row = body.path(identity);
            Input.strict(
                    row,
                    "enabled",
                    "title",
                    "introduction",
                    "benefits",
                    "contact",
                    "enrollmentGuide");
            boolean enabled = Input.bool(row, "enabled");
            String title = Input.text(row, "title", enabled ? 1 : 0, 100),
                    intro = Input.text(row, "introduction", enabled ? 1 : 0, 2000),
                    contact = Input.text(row, "contact", enabled ? 1 : 0, 300),
                    guide = Input.text(row, "enrollmentGuide", enabled ? 1 : 0, 2000);
            com.fasterxml.jackson.databind.node.ArrayNode benefits =
                    Input.array(row, "benefits", enabled ? 1 : 0, 20);
            for (JsonNode benefit : benefits)
                Input.require(
                        benefit.isTextual()
                                && !benefit.asText().trim().isEmpty()
                                && benefit.asText().length() <= 300,
                        "成长权益须为1至300字文字");
            value.set(
                    identity,
                    obj(
                            "enabled",
                            enabled,
                            "title",
                            title,
                            "introduction",
                            intro,
                            "benefits",
                            benefits,
                            "contact",
                            contact,
                            "enrollmentGuide",
                            guide));
        }
        db.tx(
                () -> {
                    db.putSetting("course_rights", value, actor);
                    access.audit("course-rights.update", "settings", "course_rights", reason);
                });
        return value;
    }
}
