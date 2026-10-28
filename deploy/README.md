# 统一平台 v2 部署

请先阅读 [实现与验收说明](../PLATFORM.md)。入口保留 Nginx Basic Auth，平台账号登录与入口密码是两层独立认证。

## 独立数据与备份

- Compose 复用原 `postgres` 服务和 `pgdata` 卷，在同一 PostgreSQL/pgvector 容器中使用独立库 `fan_ai_agent_v2` 和独立账号 `fan_platform`。旧库保持原样。
- 用户文件使用独立持久卷 `platform_files_v2`。备份应同时覆盖新数据库和此卷。
- 上线前由运维对旧数据库做独立 `pg_dump`，保存旧部署配置、卷名和恢复步骤。不要执行 `docker compose down -v` 或自动删除旧容器/卷。
- `user-center/` 和图片 MCP 子项目保留为独立源码参考，不参与新服务运行；没有导入旧账号、聊天和记忆的迁移。

## 构建与配置

1. 先运行根目录 `mvn clean test` 与前端 `npm ci && npm test && npm run build`。
2. 运行 `powershell -File deploy/build-release.ps1` 生成发布包。它使用不含本地凭据的 `application.example.yml`，而不是本地的 `application.yml`；不会打包测试、旧 MCP 凭据和演示入口。
3. 参考 `.env.example` 创建配置；已有 `.env` 不会被脚本覆盖。**旧版 `.env` 需要增加 `PLATFORM_DB_PASSWORD`，旧 `DB_PASSWORD` 不用于新库。** 可选的 `prepare-local-env.ps1` 仅在两个凭据文件都不存在时生成新凭据模板，不复制旧配置中的密钥。
   新容器首次初始化会执行 `postgres/provision-platform.sh`。已有 PostgreSQL 容器则先手动执行以下命令（环境变量应从受保护的 `.env` 加载；不要覆盖原 `DB_PASSWORD`）：

   ```sh
   export PLATFORM_DB_PASSWORD
   docker compose exec -T -e PLATFORM_DB_PASSWORD postgres sh -s < postgres/provision-platform.sh
   ```

   脚本仅创建缺失的角色、数据库和 vector 扩展；已有角色密码保持原值。升级时使用 `docker compose up -d --no-deps backend frontend`，保留原数据库容器与辅助服务。
4. 填写模型 API Key；可选 `SEARCH_API_KEY`，缺省时联网搜索显示未配置。初始化管理员设置 `ADMIN_ACCOUNT`（4–18位字母数字下划线）及 `ADMIN_PASSWORD`（至少12字符，UTF-8最多72字节）；首次创建后清空初始化密码环境变量，不会自动升级同名已注册普通用户。
   - 多模态聊天模型应设置 `DASHSCOPE_MULTI_MODEL=true`，聊天和工具调用均沿用该接口类型。
   - 向量模型默认 `qwen3.7-text-embedding-flash`，固定请求 1024 维，与平台向量表一致。更换已有知识库的向量模型时，应备份并重新向量化公共知识，避免混用不同模型生成的向量；保留用户聊天、记忆和文件数据。
5. 配好 HTTPS 反向代理后，再在 `deploy/` 运行 `docker compose up -d --build`。后端和数据库不发布公网端口；仅经 Nginx 同源访问 `/api`。

生产 `SESSION_COOKIE_SECURE=true`；这要求浏览器使用 HTTPS。仅本机 HTTP 演练时可显式设为 `false`，生产应恢复 `true`。不要把真实密钥、`.env`、`.htpasswd`、数据库备份或用户文件加入版本库。

## 上线验收与回退

- 登录/注册、CSRF、Session ID 轮换、退出、停用账户立即生效。
- 用两个测试账户检查会话、记忆、文件、任务停止和上下文授权的归属。
- 检查八种能力组合、逐轮切换、确认卡、临时限制覆盖、断线后读取状态而不重跑。
- 实际模型、embedding、SearchAPI 和 PDF 组合执行需要使用部署环境配置单独演练；自动化测试不消耗真实模型/搜索凭据。
- 保留 `frontend/nginx.conf` 中的 `auth_basic` 配置。修改入口账号密码后更新受保护的 `frontend/.htpasswd` 并重建前端；`.env` 中的入口密码变量不自动生成哈希文件。
- 回退使用已保存的旧发布包、Compose、环境文件、认证文件和旧镜像；旧库和新版库均保留。新版文件卷另行保留，两个系统的数据不混用。

当前执行调度按**单后端实例**设计，重启将未完成任务标记中断，不自动重放工具副作用。扩展多副本前，需要增加分布式租约、执行队列和实例级恢复逻辑。
