package com.campus.rag.controller;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

/**
 * 前端是 Vue 单页应用（history 路由），直接刷新 /documents 这种深链接时，
 * 浏览器会把请求打到后端，而静态资源目录里没有同名文件 → 404。
 * 这里把前端真实用到的几个路径转发到 index.html，交给前端路由接管。
 *
 * <p>为什么不用通配符（如 "/{path:[^(api)].*}"）：Spring 6 的路径匹配规则容易把
 * 静态资源（/assets/**）或接口一起吃掉，排查成本高；前端路由一共就这几个，
 * 显式列举最安全，新增页面时记得回来加一行。
 */
@Controller
public class SpaForwardController {

    @GetMapping({"/", "/login", "/register", "/qa", "/documents", "/debug", "/eval", "/feedback", "/users", "/health"})
    public String index() {
        return "forward:/index.html";
    }
}
