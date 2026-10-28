# FanAI 统一聊天平台 v2

## 实现结构

- `src/main/java/com/fan/fanaiagent/platform/`：内部账户模块、统一聊天编排器、上下文隔离、记忆解析、按轮工具权限、文件空间与接口。
- `src/main/resources/db/migration/`：Flyway 创建全新 PostgreSQL 表；Session 也持久化至此数据库。
- `fan-ai-agent-frontend/src/platform/`：统一登录/注册/聊天/资料/记忆/文件/管理员页面。Markdown 由 marked 渲染、DOMPurify 清理；不执行参考资料中的 HTML 或指令。
- 公共向量库只存公共知识，保留来源与 knowledge/example 类型；个人记忆存独立关系表，不从情话例子推断用户画像。
- 删除双智能体页面、旧接口、默认全工具注册、终端工具及旧 RAG/advisor 分支。用户原有修改涉及的 `BaseAgent`、`FanManus` 等执行类保留，但没有控制器或 Spring Bean 接入新聊天链路。嵌套 `user-center/` 保持独立，账户业务在主工程内重建为 PostgreSQL 模块，没有导入旧数据。

## 本地启动

Java 21；使用 pgvector PostgreSQL。创建**新空数据库** `fan_ai_agent_v2`，数据库用户应有创建表和 vector 扩展的权限。复制 `application.example.yml` 到本地 `application.yml`（先自行保留旧本地配置），然后设置：

```powershell
$env:PLATFORM_DB_URL='jdbc:postgresql://localhost:5432/fan_ai_agent_v2'
$env:PLATFORM_DB_USERNAME='fan_platform'
$env:PLATFORM_DB_PASSWORD='<新库密码>'
$env:DASHSCOPE_API_KEY='<模型密钥>'
$env:SEARCH_API_KEY='<可选搜索密钥>'
mvn spring-boot:run
# 另一终端，前端代理 /api -> localhost:8123
cd fan-ai-agent-frontend
npm ci
npm run dev
```

主入口强制增加 `platform` profile。DataSource 单独绑定 `platform.datasource`/`PLATFORM_DB_*`，旧 `spring.datasource` 配置不决定新数据库。Flyway 不允许自动 baseline 到旧表。向量维度默认1024（text-embedding-v3），更换 embedding 模型前同步调整 `platform.embedding-dimensions`，在新向量表验证；不在已有向量表直接更改维度。

## 身份与接口

后端只从 Session 的可信 Long 用户 ID 建立身份；对外账户 ID 为字符串。BCrypt、UTF-8 72字节上限、2小时空闲、CSRF、HttpOnly/SameSite Cookie、登录轮换 ID、退出取消运行任务。每次请求重查账户，管理员仅获得账户管理，不拥有私聊/记忆跨账户权限。执行线程显式携带不可变 `ExecutionContext`，不读取请求线程身份。

以下路径均以 `/api` 为前缀，变更请求先从 `GET /auth/csrf` 取得 `headerName` 与 `token`，附在请求头；登录后重新获取：

| 功能 | 接口 |
|---|---|
| 账户 | `POST /auth/register`、`POST /auth/login`、`GET /auth/current`、`POST /auth/logout` |
| 资料/管理 | `PATCH /user/profile`、`GET /admin/users`、`PATCH /admin/users/{id}`（启用/停用） |
| 会话 | `POST/GET /chat/conversations`、`GET /chat/conversations/{id}/messages`、`DELETE /chat/conversations/{id}` |
| 每轮流 | `POST /chat/conversations/{id}/turns`（fetch SSE） |
| 状态/停止 | `GET /chat/runs/{id}`、`POST /chat/runs/{id}/stop` |
| 仅本轮上下文 | `POST /chat/conversations/{id}/context-grants`，传用户消息 `messageId` |
| 记忆 | `GET /memories`、`POST /memory-candidates/{id}/confirm`（version）、`PATCH /memories/{id}`（version/value/scope）、`DELETE /memories/{id}?version=N` |
| 文件 | `GET /artifacts`、`GET /artifacts/{id}/download`、`DELETE /artifacts/{id}` |

流事件：start、tool、sources、artifact、notice、answer_delta、context_required、memory_candidate、error、done。每个事件带 runId/sequence。原始正文与工具/检索参考分开保存；部分文本、状态与非文本事件可从消息接口恢复。断线不重发任务；默认停止后续步骤，已有产物保留。关闭/刷新页面未收到取消事件时，服务端身份、超时与步骤限额仍有效。

请求 ID 在用户范围唯一：相同内容返回原运行状态，不重放；同 ID 不同内容返回409。每会话1轮、每用户2轮、每分钟10轮、最多8次工具调用、180秒上限；没有每日额度。注册与登录也有限频。当前模型通信不自动重试；对副作用采用更保守的零重放策略。

历史最多30条且按完整问答组裁剪，同时使用 token 预算：按 UTF-8 字节数计算保守上界，而非依赖特定供应商 tokenizer。历史默认12000、整轮默认48000；参考资料、记忆与工具结果分别限长，任务模型触及上下文预算即停止后续步骤并保留成果。

## 能力与信息边界

三个开关默认全关，保存每条消息的不可变快照，当前生成期间切换仅影响下一轮。

| 能力 | 可用内容/工具 |
|---|---|
| 全关 | 普通聊天；无公共检索、执行工具、模型内置联网 |
| love | 相关意图下按需查询本地公共恋爱库；topK=3，阈值0.5，再做词项相关性复核 |
| task | 个人文本/Markdown/PDF新产物、本人文本读取；无服务器终端 |
| web | 只读公开搜索/页面读取；单独开启时不创建服务器文件 |
| task + web | 上述能力加公开结果下载；其余任意组合按独立权限组合 |

LOVE 文本和回答在恋爱开关关闭时不进入普通模型历史。混合/不明确的关系内容从严标为 LOVE。选择上下文卡可临时带入至本轮，10分钟有效、一次性，最多8条；由此生成的答案仍保留 LOVE 作用域，后续普通轮次不会继承授权。工具执行器没有完整历史或情感叙述；可使用投影后的饮食约束，或者用户在“工具内容边界”显式给出的本轮任务内容。

服务器仅自动外发白名单公共主题/城市词。更具体的查询需填写“对外检索词”，填写即明确确认；凭据、电话/邮箱等拒绝作为检索词。确认记忆与确认外发是两回事。搜索结果、网页、工具内容都作为不可信资料，而不是新系统指令。网络客户端禁用代理与自动跳转，每跳、DNS实际连接解析、端口、协议、大小、超时均检查，私有/环回/链路本地等目标被阻断。页面读取/下载只接受本轮搜索返回的精确URL。

文件名仅显示用，实际路径为 `userId/conversationId/artifactId`；无覆盖接口，删除由用户操作确认。检查根目录、父目录、路径穿越和符号链接；数据库归属校验后才下载；单文件10MB、每用户50MB，删除墓碑异步清理，数据库事务回滚清理未提交文件。生产文件目录仅供应用账户写入，避免本地其他进程制造检查/使用竞态。

产物继承来源作用域；任务模式读取 LOVE 文件需在本轮明确选择文件 ID（或开启恋爱能力），相应回答仍标为 LOVE，不会借文件读取把私密内容带入后续普通轮次。

## 记忆与更新

- 默认只从明确陈述提取候选；确认前仅来源会话使用。确认后按作用域跨会话使用。任务约束不存长期候选。关闭资料中的候选提取选项，不影响当轮明确约束，也不删除已有已确认记忆。
- 按主体/事实键分层：PREFERENCE、TEMPORARY、TASK、RETRACT。临时限制优先长期偏好，恢复/纠错使用撤销事件，稳定偏好仍保留。`最近`不会被编造为日期；有明确未来N天才生成期限；健康限制到期仍待复核。
- 本人明确的饮食偏好/限制只投影“喜欢辣/不吃辣”等最小事实为 COMMON，确认卡显示该作用域，不带入感冒等原始私密叙述；关闭恋爱模式仍遵守已确认饮食限制，避免旧口味偏好反而恢复生效。第三方饮食事实仍属 LOVE，工具只看到匿名用餐者编号和必要约束。
- 当前纠正会筛掉矛盾历史整组问答，不把助手猜测当作用户证据。版本、预期被替代 ID、用户行锁和唯一有效索引保护并发确认/编辑；过时卡返回409，不静默覆盖。
- 开轮在用户行锁内固定完整候选事实快照，再与本轮陈述解析；保留被临时覆盖的长期层，解除后可以恢复。运行期间的新确认只影响后续轮次。面板编辑单独标记 USER_EDIT 来源，不冒充原消息内容。
- 删除来源会话级联删除该会话记忆，文件墓碑异步删除。删除记忆不删除原始聊天；页面明确告知，并可从侧栏删除关联会话。
- 提取/存储异常不阻塞回答；没有保存成功结果就不显示已保存。

首版采用**保守规则提取**，支持吃辣/香菜、称呼、回答风格、关系状态、明确“记住：”陈述、常见否认/引用/假设/创作和主体区分；未识别的自由文本不自动生成画像。它不是覆盖任意自然语言的事实抽取器；复杂表达可由用户在记忆面板确认/编辑。后续扩展模型抽取也必须保留来源校验、确认、版本和权限，不直接落库。

## 回归与待上线验证

```powershell
mvn clean test
cd fan-ai-agent-frontend
npm ci
npm test
npm run build
```

测试使用临时嵌入式 PostgreSQL（首次下载平台二进制），不连接旧库、不请求真实模型/搜索。测试层覆盖：8组合权限、实际工具回调、隐式联网关闭、worker最小上下文、引用/秘密/主体、临时偏好覆盖/恢复、历史整组预算、CSRF/跨用户/幂等、记忆确认和来源删除、真实HTTP登录/JDBC Session/SSE/事件恢复/退出。前端测试覆盖 UTF-8/CRLF/任意分包/损坏流，不做自动请求重放。

前端使用账户代际标记丢弃旧账户的延迟响应，BroadcastChannel 通知其他标签清空旧缓存；退出网络失败会保留明确错误，不声称服务端已退出。对外检索词、工具内容授权和上下文授权只用于发送时快照，不默认延续到下一轮。

仍需部署环境演练真实模型与 SearchAPI、pgvector 实际embedding检索质量、完整组合计划/PDF、TLS 与 Nginx。代码与规则测试不代表真实模型零幻觉；未知事实/低相关检索不注入画像，并通过真实来源和工具成功结果限制答案依据。无图像搜索/MCP/服务器终端自动注册。

当前进程调度面向单实例；多副本、完整通用文件上传/OCR、模型自由语义抽取、头像上传和管理员任意字段/权限编辑不在这版开放。账户管理首版为列表与启用/停用，资料由本人编辑。原项目中的独立用户中心及旧数据库不作为清理对象。部署与回退步骤见 `deploy/README.md`。
