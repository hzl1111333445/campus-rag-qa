package com.campus.rag.service;

/**
 * 会话归属判定 —— 整个系统的"谁能看谁的会话"只有这一条规则。
 *
 * <p>为什么抽成一个纯函数（不碰数据库、不碰 Spring）：
 * <ol>
 *   <li>规则唯一：Controller 里只调用它，不存在"某个接口忘了判断"；</li>
 *   <li>可单测：不启数据库就能把 6 种组合全测一遍；</li>
 *   <li>好演进：以后要加"老师能看本班学生"，改这里一处即可。</li>
 * </ol>
 *
 * <p>三种身份：
 * <ul>
 *   <li><b>匿名</b>：没有令牌（评测脚本、未登录的 curl）。能提问但不产生归属会话；</li>
 *   <li><b>学生</b>：只能看自己创建的；</li>
 *   <li><b>管理员</b>：能看全部（包括匿名会话，用于排查评测/异常）。</li>
 * </ul>
 *
 * <p><b>越权一律当"不存在"处理</b>（Controller 里抛 404 语义的异常）：
 * 如果回答"存在但你没权限"，等于告诉对方这个 id 有效，可以被用来枚举有多少会话。
 */
public final class SessionAccess {

    private SessionAccess() {
    }

    /**
     * 能否读取/修改这条会话。
     *
     * @param ownerUserId     会话的归属用户；null 表示匿名会话
     * @param requesterUserId 请求者的用户 id；null 表示未登录
     * @param admin           请求者是否管理员
     */
    public static boolean can(Long ownerUserId, Long requesterUserId, boolean admin) {
        if (admin) {
            // 管理员看全部：排查"某次评测为什么答错"时需要连匿名会话一起看
            return true;
        }
        if (requesterUserId == null) {
            // 未登录：匿名会话也不给看。否则任何人遍历 id 就能读到别人的匿名提问
            return false;
        }
        return requesterUserId.equals(ownerUserId);
    }

    /** 新建会话时，归属人就是请求者；未登录则记 null（匿名）。 */
    public static Long ownerFor(Long requesterUserId) {
        return requesterUserId;
    }
}
