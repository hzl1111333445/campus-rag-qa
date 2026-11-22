package com.campus.rag.config;

import com.campus.rag.service.AuthService;
import com.campus.rag.service.UserDao;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/**
 * 首次启动时建两个默认账号，免得"新环境跑起来没人能登录"。
 *
 * <p>只在账号不存在时才建，所以重启不会覆盖你改过的密码。
 * <b>上线前必须改掉默认密码</b>——这两个账号只适合本地开发与答辩演示。
 */
@Component
public class DataInitializer implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(DataInitializer.class);

    private final UserDao userDao;
    private final AuthService authService;

    public DataInitializer(UserDao userDao, AuthService authService) {
        this.userDao = userDao;
        this.authService = authService;
    }

    @Override
    public void run(ApplicationArguments args) {
        ensure("admin", "admin123", AuthService.ROLE_ADMIN, "系统管理员");
        ensure("student", "student123", AuthService.ROLE_STUDENT, "在校学生");
    }

    private void ensure(String username, String password, String role, String displayName) {
        if (userDao.findByUsername(username) != null) {
            return;
        }
        authService.createUser(username, password, role, displayName);
        log.info("已创建默认账号：{} / {}（角色 {}）——上线前请修改密码", username, password, role);
    }
}
