package com.jianyanji.content;

import static com.jianyanji.common.J.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jianyanji.common.*;
import org.junit.jupiter.api.Test;

/** 服务端身份权益选择，覆盖已失效主理人资格及未配置的真实空状态。 */
class CourseRightsServiceTest {
    @Test
    void effectiveIdentitySelectsOnlyCurrentRightsAndExpiredPrincipalGetsVisitorRights() {
        Db db = mock(Db.class);
        CourseRightsService service = new CourseRightsService(db, mock(Access.class));
        when(db.setting("course_rights"))
                .thenReturn(
                        obj(
                                "B",
                                obj("enabled", true, "title", "主理人成长"),
                                "C",
                                obj("enabled", true, "title", "店主成长"),
                                "D",
                                obj("enabled", true, "title", "游客介绍")));
        assertEquals("店主成长", s(service.mine(obj("identity", "C")).path("rights"), "title"));
        ObjectNode expired =
                service.mine(obj("identity", "B", "identity_valid_until", "2020-01-01T00:00:00Z"));
        assertEquals("D", s(expired, "identity"));
        assertEquals("游客介绍", s(expired.path("rights"), "title"));
        when(db.setting("course_rights")).thenReturn(null);
        assertFalse(b(service.mine(obj("identity", "C")), "configured"));
        assertEquals(0, service.overview().path("rights").size());
    }

    @Test
    void enabledCourseNeedsRealContactAndCannotCreateEnrollmentRecords() {
        Db db = mock(Db.class);
        CourseRightsService service = new CourseRightsService(db, mock(Access.class));
        ObjectNode row =
                obj(
                        "enabled",
                        true,
                        "title",
                        "成长权益",
                        "introduction",
                        "介绍",
                        "benefits",
                        arr("线下课程介绍"),
                        "contact",
                        "",
                        "enrollmentGuide",
                        "联系品牌客服");
        assertThrows(
                BusinessException.class,
                () -> service.save(obj("D", row, "C", row, "B", row, "reason", "维护"), 7));
        verifyNoInteractions(db);
        assertThrows(
                BusinessException.class, () -> service.save(obj("userId", 8, "identity", "B"), 7));
    }
}
