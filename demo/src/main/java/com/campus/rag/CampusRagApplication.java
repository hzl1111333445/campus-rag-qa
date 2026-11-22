package com.campus.rag;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * 程序入口。
 *
 * @SpringBootApplication 这句话干了三件事（现在只要知道大概）：
 *  1. 自动配置：看到 classpath 里有 web 依赖，就自动帮你把 Tomcat、Spring MVC 装好
 *  2. 组件扫描：扫描本包及子包下带 @Service / @Controller / @Component 的类，塞进 Spring 容器
 *  3. 标记配置类
 *
 * 注意：它只扫描 com.campus.rag 及其子包。所以你的代码都要放在这个包下面。
 */
@SpringBootApplication
public class CampusRagApplication {

    public static void main(String[] args) {
        SpringApplication.run(CampusRagApplication.class, args);
    }
}
