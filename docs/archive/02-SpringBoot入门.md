# Spring Boot 入门（第二份原理文档）

> 配合 `01-原理大纲.md` 第 3 章看。这份专门讲 Spring Boot：
> 它替你干了什么、你项目里那 5 个文件分别在干嘛、请求是怎么进出你的代码的。
>
> 目标：看完你能回答"为什么要有 Spring Boot""@RestController 是什么意思""请求怎么走到我的方法里"。

---

## 1. 一句话：Spring Boot 是"脚手架 + 胶水"

你真正想写的业务代码只有三件：**收文件、解析文本、检索回答**。

但一个 Java 程序想对外提供网页接口，底下还有一堆脏活：

| 脏活 | 没有 Spring Boot 时你要写 |
|---|---|
| 启动 Web 服务器 | 手写 `Tomcat tomcat = new Tomcat(); tomcat.setPort(8080); ...` |
| 把 `/api/health` 映射到某个方法 | 配 `web.xml` 或手写 Servlet 类 |
| 把 Java 对象转成 JSON | 手写字符串拼接或引入 Gson 再手动调用 |
| 管理数据库连接 | 每次 `DriverManager.getConnection()`，忘了关就连接泄漏 |
| 读配置 | 手动读 properties 文件、解析、类型转换 |
| 管理对象依赖 | 到处 `new`，改一个实现要改十几处 |

Spring Boot 把这些**全包了**，你只留业务代码。

> 常见误解：Spring Boot 不是"另一个语言"，也不是"Web 框架"。它是**整合层**：把 Spring 生态（Web、数据库、缓存、安全）打包成开箱可用的组合，并内置 Tomcat。

---

## 2. 三个核心机制（就这三个，别贪多）

### 机制一：依赖注入（IoC 容器）

**问题**：如果 `DocumentController` 需要 `DocumentDao`，最直觉的写法是：

```java
public DocumentController() {
    this.documentDao = new DocumentDao();      // 自己 new
}
```

坏处：`DocumentDao` 想加个参数怎么办？想换成测试用的假实现怎么办？每个用到的地方都要改。

**Spring 的做法**：把创建对象的权力收走，交给"容器"。你只声明"我需要什么"：

```java
public DocumentController(DocumentStorage storage, DocumentDao documentDao) {
    this.storage = storage;
    this.documentDao = documentDao;
}
```

容器启动时扫一遍所有类，发现：
- `DocumentStorage` 上有 `@Service` → 造一个实例放容器里
- `DocumentDao` 上有 `@Repository` → 造一个
- `DocumentController` 上有 `@RestController` → 造的时候发现构造器要两个参数 → 从容器里拿现成的塞进来

这叫**构造器注入**，是目前推荐写法（比字段上写 `@Autowired` 好：依赖清晰、能 final、好测试）。

**一句话记住**：`@Service`、`@Repository`、`@Controller`、`@Component` 都是同一个意思——"这个类交给容器管"。区别只是语义（给人看的）。

### 机制二：自动配置（starter 依赖）

你在 `pom.xml` 里只写了一行：

```xml
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-web</artifactId>
</dependency>
```

这一个 starter 实际带进来了：内嵌 Tomcat、Spring MVC、Jackson（JSON 转换）、参数校验……

而且启动时 Spring 会**根据 classpath 里有什么，自动做对应配置**：

- 发现 classpath 有 Tomcat 类 → 自动启动内嵌 Tomcat、监听 8080（或你配的端口）
- 发现 classpath 有 HikariCP + MySQL 驱动 → 自动创建数据源、连接池
- 发现 `application.yml` 里有 `spring.datasource.url` → 用它连库

这就是为什么你**只写了 yml、没写一行数据源代码**，`JdbcTemplate` 就能用。

副作用：报错信息常常离你写代码的地方很远（比如"Failed to determine a suitable driver class"，实际原因是你 yml 缩进错了）。排查时**先看配置，再看代码**。

### 机制三：约定优于配置

| 约定 | 含义 |
|---|---|
| 启动类放在最外层包 | 它只扫描自己所在包及子包 |
| 配置写 `src/main/resources/application.yml` | 不用告诉它去哪找 |
| Java 代码放 `src/main/java` | Maven 约定 |
| 默认端口 8080 | 想换就 `server.port` |

**最常踩的坑**：启动类放在了 `com.campus.rag.config` 里，而 Controller 在 `com.campus.rag.controller` → **扫不到，接口 404**。

---

## 3. `@SpringBootApplication` 里藏着三个注解

```java
@SpringBootApplication
public class CampusRagApplication { ... }
```

它等价于：

```java
@SpringBootConfiguration   // 这是个配置类
@EnableAutoConfiguration   // 开启自动配置（上面机制二）
@ComponentScan             // 扫描本包及子包里的 @Component/@Service/@Controller/@Repository
public class CampusRagApplication { ... }
```

所以你的包结构必须是：

```
com.campus.rag
├── CampusRagApplication        ← 启动类在最外层
├── controller/                 ← 会被扫到
│   ├── DocumentController
│   └── HealthController
└── service/
    ├── DocumentStorage
    ├── DocumentDao
    └── TextSplitter            ← 注意：它没有注解，因为全是静态方法，不需要容器管
```

---

## 4. 你项目里每个文件在干什么（对着代码看）

### `CampusRagApplication.java`
程序入口。`SpringApplication.run` 做三件事：创建容器 → 扫描并实例化所有 Bean → 启动 Web 服务器。

### `application.yml`
配置。分层级：`server.port`、`spring.datasource.*`、`campus.upload-dir`、`logging.level`。

**YAML 的规则只有一条：缩进就是层级。** 多缩两格就变成了另一个父节点的子项（你踩过：`spring:` 套 `spring:` → `spring.spring.datasource.url`，等于没配）。

### `controller/HealthController.java`
```java
@RestController
@RequestMapping("/api/health")
public class HealthController {
    @Value("${spring.application.name}")
    private String appName;

    @GetMapping
    public Map<String, Object> health() { ... }
}
```
- `@RestController` = `@Controller` + `@ResponseBody`：方法返回值直接作为响应体（对象自动转 JSON）
- `@RequestMapping("/api/health")`：类级前缀
- `@GetMapping`：类级前缀 + 方法 = 最终路径 `GET /api/health`
- `@Value("${...}")`：从 yml 取值注入这个字段
- 返回 `Map` → Jackson 自动转成 `{"status":"UP",...}`

### `service/DocumentStorage.java`
`@Service` + 构造器注入配置值。职责单一：把字节写到磁盘。
注意构造器里 `Files.createDirectories(dir)`：目录不存在就先建，否则第一次上传直接抛异常。

### `service/DocumentDao.java`
`@Repository` + 构造器注入 `JdbcTemplate`。
`JdbcTemplate` 是 Spring 给的"SQL 执行器"：帮你拿连接、执行、映射结果、关连接（异常时也关）。

| 方法 | 等价 SQL |
|---|---|
| `jdbc.update(sql, 参数...)` | INSERT / UPDATE / DELETE |
| `jdbc.queryForList(sql, ...)` | SELECT 多行，每行是 `Map<列名,值>` |
| `jdbc.queryForObject(sql, Long.class)` | SELECT 单值（如 COUNT(*)） |

`KeyHolder` 的作用：MySQL 插入后才知道自增 id，用它可以拿回来。

### `service/TextSplitter.java`
**没有注解**。因为它全是静态方法、不持有状态，不需要容器管理。什么时候需要 `@Service`？——当它要注入别的 Bean（比如 `DocumentParser`、`DocumentDao`）时。

### `controller/DocumentController.java`
`@RestController` + 两个依赖（Storage、Dao）。`@RequestParam("file") MultipartFile file` 从 multipart 表单里取文件。

---

## 5. 一条请求的完整旅程

你在终端敲了：

```powershell
curl.exe -F "file=@xxx.pdf" http://localhost:8081/api/document/upload
```

内部发生了 9 步：

```
1. curl 把文件 + 表单字段编码成 multipart 请求，通过 TCP 发到 8081
2. 内嵌 Tomcat 收到请求，解析 HTTP（方法 POST、路径 /api/document/upload、body）
3. Tomcat 把请求交给 Spring MVC 的 DispatcherServlet（前端控制器，唯一入口）
4. DispatcherServlet 问 HandlerMapping：这个路径谁处理？
   → 查到 DocumentController.upload 方法（启动时已扫描登记过）
5. 参数绑定：方法要 MultipartFile file → 从 multipart body 里取出名为 file 的部分
6. 通过反射调用 upload(file)          ← 你的代码从这一行开始执行
7. 你的代码：storage.save(...) 写磁盘；documentDao.insert(...) 写库
8. 返回 Map → Jackson 序列化成 JSON 字符串
9. 包成 HTTP 200 响应（Content-Type: application/json）发回 curl
```

**这张图能解释很多"玄学"**：
- 404 = 第 4 步没查到（路径错 / Controller 没被扫描到 / 包结构不对）
- 500 = 第 6~8 步抛异常（代码 bug 或数据库连不上）
- 405 = 路径对但方法不对（你用了 `@GetMapping` 而客户端发 POST）
- 400 = 第 5 步参数绑定失败（缺 `file` 参数、类型不匹配）

---

## 6. 常用注解速查（够用就行）

| 注解 | 一句话 | 例子 |
|---|---|---|
| `@SpringBootApplication` | 启动类 | 最外层那个类 |
| `@RestController` | 这个类的方法返回 JSON | `DocumentController` |
| `@RequestMapping` | 类/方法的路径前缀 | `@RequestMapping("/api/document")` |
| `@GetMapping` / `@PostMapping` | GET / POST 接口 | `@PostMapping("/upload")` |
| `@RequestParam` | 取查询参数或表单字段 | `@RequestParam("file")` |
| `@RequestBody` | 取 JSON 请求体并转成对象 | `@RequestBody AskRequest req` |
| `@PathVariable` | 取路径里的变量 | `/document/{id}` + `@PathVariable long id` |
| `@Service` | 业务层，交给容器 | `DocumentStorage` |
| `@Repository` | 数据访问层，交给容器 | `DocumentDao` |
| `@Component` | 通用组件，交给容器 | `DocumentParser` |
| `@Value("${k}")` | 注入单个配置值 | `@Value("${campus.upload-dir}")` |
| `@ConfigurationProperties` | 注入一组配置到对象 | 一次绑定 `campus.rag.*` |
| `@Configuration` + `@Bean` | 手动注册一个对象到容器 | 按配置决定造哪种实现 |
| `@Transactional` | 这个方法里的数据库操作要么全成功要么全回滚 | 一次插文档 + N 个块 |
| `@ExceptionHandler` | 统一处理异常，返回友好错误 | 参数错误返回 400 |

---

## 7. 配置的两个进阶点

### `@Value` vs `@ConfigurationProperties`

配置少（1~3 个值）用 `@Value`；配置成组（`campus.rag.chunk-size`、`campus.rag.top-k`……）用一个类绑定：

```java
@ConfigurationProperties(prefix = "campus.rag")
public class RagProperties {
    private int chunkSize = 220;
    private int topK = 3;
    // getter / setter
}
```

好处：有默认值、有类型检查、IDE 能提示、不用散落一堆 `@Value`。

### profile（多环境配置）

`application.yml`（公共）+ `application-local.yml`（本地库密码）+ `application-prod.yml`（服务器），用 `spring.profiles.active` 切换。

**关键习惯**：密码不要提交到 Git。本地配置放 `application-local.yml`，并写进 `.gitignore`。

---

## 8. 数据库这层：你还需要知道的三件事

1. **连接池（HikariCP）**：启动时建好几条连接复用，不是每次请求都连库。所以"启动成功"≠"数据库连得上"——连接是懒建立的，直到第一次执行 SQL 才暴露密码错误。
2. **`JdbcTemplate` 不用自己关连接**：它内部 try-finally 帮你关。
3. **`@Transactional`**：一次操作涉及多张表（插文档 + 插 50 个块），中途失败要回滚，否则会留下"文档存在但块不全"的脏数据。后面做入库时我们会用上。

---

## 9. 报错对照表（你踩过的都在里面）

| 报错 | 真实原因 | 怎么修 |
|---|---|---|
| `Web server failed to start. Port 8081 was already in use` | 上一个实例没停干净 | 结束占用端口的进程，或换端口 |
| `Failed to configure a DataSource: 'url' attribute is not specified` | yml 里 datatsource 缩进错 / 没配 | 检查是不是 `spring.spring.datasource` |
| `Access denied for user 'root'@'localhost'` | 数据库密码错 | 改 yml 密码 |
| `无法编译为 JVM 目标 21 ... 回退 SDK 版本 8` | IDEA 项目 SDK / 语言级别是 Java 8 | 项目结构里换成真 JDK 21 |
| `类文件具有错误的版本 55.0, 应为 52.0` | Maven 用了 JDK 8 编译 | `JAVA_HOME` 指到 JDK 21 |
| 接口 404 | 路径错 / Controller 不在启动类子包下 | 检查包结构 |
| 接口 405 | 方法不匹配 | GET 还是 POST |
| `required request part 'file' is not present` | 上传时字段名不是 `file` | curl 的 `-F "file=@..."` |
| 控制台中文乱码但库里正常 | PowerShell 5.1 按 GBK 解码 UTF-8 | 看数据库，别信控制台 |
| `PDF 里没有提取到文字` | 扫描件没有文字层 | 先 OCR |

---

## 10. 自测 8 题（答不上来就回头查对应章节）

1. Spring Boot 替你干了哪四件脏活？
2. `@Service` 和 `@Repository` 有什么区别？（提示：语义 vs 功能）
3. 为什么启动类要放在最外层包？
4. 一条 `GET /api/document/page` 请求，在 Spring 内部按什么顺序被处理？
5. 404、405、400、500 分别最可能是什么原因？
6. 为什么"启动成功"不代表"数据库连得上"？
7. `JdbcTemplate` 和直接写 JDBC 相比，省了你哪些代码？
8. 你的 `TextSplitter` 为什么没有加 `@Service`？

---

## 11. 和 RAG 的分工（回扣第一份文档）

```
Spring Boot 负责「怎么把能力暴露出去」：HTTP、JSON、配置、数据库、连接池
RAG 负责「能力本身怎么算」：解析、切分、向量、检索、融合、重排、引用校验
```

所以：**Spring Boot 写不好，接口就不通；RAG 写不好，答得就不准。**
两件事都要会，但先保证接口通——不通就没法验证 RAG 效果。这也是我们现在的施工顺序。
