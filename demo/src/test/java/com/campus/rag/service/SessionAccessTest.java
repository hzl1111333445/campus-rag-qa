package com.campus.rag.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 数据隔离的判定规则（纯函数，不连数据库）。
 *
 * <p>为什么单独给这几行写测试：隔离规则一旦写错，错误方向是"多看"，
 * 也就是越权——这是安全问题，不是功能瑕疵。把它压成纯函数再测，
 * 比每次启服务手工点页面可靠得多。
 */
class SessionAccessTest {

    @Test
    @DisplayName("本人可以看自己的会话")
    void ownerCanAccess() {
        assertTrue(SessionAccess.can(7L, 7L, false));
    }

    @Test
    @DisplayName("别人不能看我的会话")
    void othersCannotAccess() {
        assertFalse(SessionAccess.can(7L, 8L, false));
    }

    @Test
    @DisplayName("管理员可以看任何人的会话")
    void adminCanAccessAll() {
        assertTrue(SessionAccess.can(7L, 8L, true));
        assertTrue(SessionAccess.can(null, 8L, true));
    }

    @Test
    @DisplayName("匿名会话（user_id 为空）只有管理员能看到")
    void anonymousOnlyVisibleToAdmin() {
        // 评测脚本不带 token 提问产生的会话属于这种：留空而不是随便归给某个账号
        assertTrue(SessionAccess.can(null, null, true));
        assertFalse(SessionAccess.can(null, 7L, false));
    }

    @Test
    @DisplayName("未登录的请求者拿不到任何会话")
    void anonymousRequesterSeesNothing() {
        assertFalse(SessionAccess.can(null, null, false));
        assertFalse(SessionAccess.can(7L, null, false));
    }

    @Test
    @DisplayName("归属人 = 登录人；未登录则为 null（匿名）")
    void ownerForMapsRequester() {
        assertEquals(7L, SessionAccess.ownerFor(7L));
        assertNull(SessionAccess.ownerFor(null));
    }
}
