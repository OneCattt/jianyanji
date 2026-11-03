package com.jianyanji.identity;

import static com.jianyanji.common.J.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jianyanji.common.*;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.mock.web.MockHttpServletRequest;

/** 登录复用微信账号、手机号服务端绑定及昵称更新不覆盖手机的回归。 */
class WechatLoginControllerTest {
    @Test
    void phoneLoginReusesOriginalAccountAndPreservesNicknameAndBusinessOwnership() {
        Db db = transactionalDb();
        ObjectNode old = obj("id",7,"nickname","老客户","phone","13900139000","identity","B");
        when(db.one(anyString(), any())).thenReturn(old);
        Access access = access(old);
        WechatIdentityClient wechat = mock(WechatIdentityClient.class);
        when(wechat.openid("login-code")).thenReturn("existing-openid");
        when(wechat.phone(eq("phone-code"), anyString())).thenReturn("13800138000");
        ObjectNode result = (ObjectNode) controller(db, access, wechat).wechat(obj("code","login-code","phoneCode","phone-code"));
        assertEquals(7, l(result.path("user"),"id"));
        assertEquals("老客户", s(result.path("user"),"nickname"));
        assertEquals("B", s(result.path("user"),"identity"));
        verify(db).one("SELECT * FROM users WHERE openid=? AND is_deleted=0", "existing-openid");
        verify(db, never()).insert(startsWith("INSERT INTO users"), any());
        verify(db).update(contains("phone_verified_at=CURRENT_TIMESTAMP(3)"), eq("13800138000"), eq(7L), eq(7L));
        verify(access).issueSession("user",7L);
        assertTrue(mockingDetails(db).getInvocations().stream().noneMatch(call -> call.getArguments().length > 0 && call.getArgument(0) instanceof String && call.getArgument(0).toString().matches("(?is).*(wallet|orders|identity=).*")));
    }

    @Test
    void rejectedPhoneExchangeDoesNotCreateUserChangeProfileOrIssueSession() {
        Db db = transactionalDb();
        Access access = access(obj("id",7));
        WechatIdentityClient wechat = mock(WechatIdentityClient.class);
        when(wechat.openid("code")).thenReturn("existing-openid");
        when(wechat.phone(anyString(), anyString())).thenThrow(new BusinessException(502,"WECHAT_AUTH_FAILED","授权失败"));
        assertThrows(BusinessException.class, () -> controller(db,access,wechat).wechat(obj("code","code","phoneCode","phone-code")));
        verifyNoInteractions(db);
        verify(access,never()).issueSession(anyString(),anyLong());
    }

    @Test
    void nicknameOnlyUpdateNeverWritesStalePhoneOrValidationState() {
        Db db = transactionalDb();
        ObjectNode user = obj("id",7,"nickname","旧昵称","phone","13800138000","phone_verified_at","2026-11-03T00:00:00Z");
        when(db.one(anyString(),any())).thenReturn(user);
        controller(db, access(user), mock(WechatIdentityClient.class)).profile(null, obj("nickname","最终昵称"));
        verify(db).update(contains("SET nickname=?"),eq("最终昵称"),eq(7L),eq(7L));
        assertTrue(mockingDetails(db).getInvocations().stream().noneMatch(call -> call.getMethod().getName().equals("update") && call.getArgument(0).toString().contains("phone")));
    }

    private Db transactionalDb() {
        Db db = mock(Db.class);
        when(db.tx(any(Supplier.class))).thenAnswer(call -> ((Supplier<?>) call.getArgument(0)).get());
        return db;
    }

    private Access access(ObjectNode user) {
        Access access = mock(Access.class);
        when(access.request()).thenReturn(new MockHttpServletRequest());
        when(access.user()).thenReturn(user);
        when(access.issueSession(anyString(),anyLong())).thenReturn("session-token");
        return access;
    }

    private IdentityController controller(Db db, Access access, WechatIdentityClient wechat) {
        return new IdentityController(db,access,false,false,wechat,new MockEnvironment());
    }
}
