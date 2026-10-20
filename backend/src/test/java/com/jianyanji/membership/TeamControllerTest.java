package com.jianyanji.membership;

import static com.jianyanji.common.J.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jianyanji.common.*;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/** 店主及失效主理人即使直调接口也无法查询团队或他人订单。 */
class TeamControllerTest {
    @Test
    void storeCannotReadTeamOrOrdersEvenByDirectUrl() throws Exception {
        Db db = mock(Db.class);
        Access access = mock(Access.class);
        when(access.user()).thenReturn(obj("id", 7, "identity", "C"));
        org.springframework.test.web.servlet.MockMvc mvc =
                MockMvcBuilders.standaloneSetup(new TeamController(db, access))
                        .setControllerAdvice(new ApiErrors(access))
                        .build();
        mvc.perform(get("/api/team")).andExpect(status().isForbidden());
        mvc.perform(get("/api/team/orders")).andExpect(status().isForbidden());
        verifyNoInteractions(db);
    }

    @Test
    void expiredPrincipalCannotRetainTeamAccess() throws Exception {
        Db db = mock(Db.class);
        Access access = mock(Access.class);
        when(access.user())
                .thenReturn(
                        obj(
                                "id",
                                7,
                                "identity",
                                "B",
                                "identity_valid_until",
                                "2020-01-01T00:00:00Z"));
        MockMvcBuilders.standaloneSetup(new TeamController(db, access))
                .setControllerAdvice(new ApiErrors(access))
                .build()
                .perform(get("/api/team/orders"))
                .andExpect(status().isForbidden());
        verifyNoInteractions(db);
    }
}
