# Fan AI Agent

一个基于 **Java 21、Spring Boot、Spring AI 和 Vue 3** 的全栈 AI 应用。项目提供恋爱咨询助手、支持工具调用的 FanManus 智能体，以及独立的图片搜索 MCP 服务。

## 功能特性

- 恋爱咨询：同步与 SSE 流式对话、会话窗口记忆、RAG 知识库增强。
- 智能体执行：基于 ReAct 的多步骤规划与工具调用。
- 内置工具：网页搜索、网页抓取、文件读写、资源下载、PDF 生成和终端操作。
- MCP 集成：高德地图 stdio MCP 与 Pexels 图片搜索 SSE MCP。
- Web 前端：Vue 3、Vue Router、Axios 和 Vite。
- API 文档：Springdoc OpenAPI 与 Knife4j。

## 技术栈

| 层级 | 技术 |
| --- | --- |
| 后端 | Java 21、Spring Boot 3.4、Spring AI 1.0 |
| 模型 | 阿里云百炼 / DashScope，可选 Ollama |
| RAG | Spring AI VectorStore；预留 PostgreSQL + PgVector |
| MCP | Spring AI MCP、Node.js MCP Server |
| 前端 | Vue 3、Vite 4、Vue Router、Axios |
| 构建 | Maven Wrapper、npm、Docker、Nginx |

## 项目结构

```text
.
├── src/                              # 主后端源码与知识库文档
├── fan-ai-agent-frontend/            # Vue 前端
├── fan-image-search-mcp-server/      # 图片搜索 MCP 服务
├── prompt_optimization/              # Prompt 迭代记录
├── .env.example                      # 环境变量模板
├── Dockerfile
└── pom.xml
```

运行时生成的文件保存在 `tmp/`，构建产物位于各模块的 `target/` 或前端 `dist/`；这些目录均已加入 `.gitignore`。

## 环境要求

- JDK 21
- Maven 3.9+，或直接使用项目自带的 Maven Wrapper
- Node.js 20+ 与 npm
- 可用的 DashScope、SearchAPI、Pexels 和高德地图凭据（按实际使用的功能配置）
- 可选：Ollama、PostgreSQL 与 PgVector

## 配置

项目只提交配置模板，真实密钥通过环境变量注入。

1. 复制 `.env.example` 为 `.env` 并填写本地值。
2. 将这些变量导入当前终端或 IDE 的运行配置；Spring Boot 和 npm 不会自动读取根目录 `.env`。
3. 不要提交 `.env`、私钥、证书、IDE 配置、日志或构建产物。

主要变量：

| 变量 | 用途 | 必需性 |
| --- | --- | --- |
| `DASHSCOPE_API_KEY` | 对话及向量模型凭据 | 主后端必需 |
| `DASHSCOPE_BASE_URL` | DashScope API 基地址 | 可选，有默认值 |
| `DASHSCOPE_CHAT_MODEL` | 对话模型 | 可选，有默认值 |
| `DASHSCOPE_EMBEDDING_MODEL` | 向量模型 | 可选，有默认值 |
| `SEARCH_API_KEY` | 网页搜索工具 | 使用该工具时必需 |
| `PEXELS_API_KEY` | 图片搜索 MCP | 使用图片搜索时必需 |
| `AMAP_MAPS_API_KEY` | 高德地图 MCP | 使用地图工具时必需 |
| `DB_URL`、`DB_USERNAME`、`DB_PASSWORD` | PostgreSQL / PgVector | 启用 PgVector 时必需 |

PowerShell 示例：

```powershell
$env:DASHSCOPE_API_KEY = "your_key"
$env:SEARCH_API_KEY = "your_key"
$env:PEXELS_API_KEY = "your_key"
$env:AMAP_MAPS_API_KEY = "your_key"
```

> 已经出现在本地源码中的历史密钥应在对应平台立即轮换；仅从代码中删除并不等于吊销凭据。

## 本地运行

### 1. 启动图片搜索 MCP

```powershell
.\fan-image-search-mcp-server\mvnw.cmd `
  -f fan-image-search-mcp-server\pom.xml spring-boot:run
```

默认监听 `http://localhost:8127`。

### 2. 启动主后端

```powershell
.\mvnw.cmd spring-boot:run
```

默认地址：

- API：`http://localhost:8123/api`
- Swagger UI：`http://localhost:8123/api/swagger-ui.html`
- OpenAPI JSON：`http://localhost:8123/api/v3/api-docs`

### 3. 启动前端

```powershell
npm --prefix fan-ai-agent-frontend ci
npm --prefix fan-ai-agent-frontend run dev
```

浏览器访问 `http://localhost:3000`。

## 构建与测试

```powershell
# 主后端隔离冒烟测试与打包
.\mvnw.cmd -Dtest=BackendRenameSmokeTest package

# 图片 MCP 测试与打包
.\fan-image-search-mcp-server\mvnw.cmd `
  -f fan-image-search-mcp-server\pom.xml `
  -Dtest=FanImageSearchMcpServerApplicationTests package

# 前端生产构建
npm --prefix fan-ai-agent-frontend run build
```

部分业务测试会访问外部模型、搜索服务或文件系统，运行前请确认凭据、网络和调用费用。

## API

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| `GET` | `/api/health` | 健康检查 |
| `GET` | `/api/ai/love_app/chat/sync` | 同步恋爱咨询，参数为 `message`、`chatId` |
| `GET` | `/api/ai/love_app/chat/sse` | SSE 流式恋爱咨询，参数为 `message`、`chatId` |
| `GET` | `/api/ai/manus/chat` | 执行智能体任务，参数为 `message` |
| `GET` | `/api/v3/api-docs` | OpenAPI 文档 |

## Docker 部署

前端生产环境默认使用同域 `/api`，附带的 Nginx 配置将请求代理到 `http://host.docker.internal:8123/api/`。Linux Docker 需要增加 `host.docker.internal:host-gateway` 映射，或把 upstream 调整为实际后端服务名。

后端容器化时还需保证：

- `IMAGE_MCP_URL` 指向可访问的图片 MCP 地址；
- 运行环境已注入所需密钥；
- 高德 MCP 所需的 Node.js / `npx` 可用；
- 不把本地 `.env` 打包进镜像。

## 已知限制

- 默认知识库使用内存 `SimpleVectorStore`，重启后会重新初始化。
- 会话历史默认使用内存窗口，未提供跨进程持久化查询。
- PDF 工具目前以文本输出为主，不自动嵌入下载图片。
- 智能体和部分测试会产生外部 API 调用及费用。

## 参与贡献

1. Fork 仓库并创建功能分支。
2. 保持提交聚焦，补充必要测试和文档。
3. 提交前运行后端测试、图片 MCP 测试和前端构建。
4. 确认 `git diff --cached` 中没有密钥、个人数据或生成文件。
5. 提交 Pull Request，并说明变更目的和验证方式。

## License

当前仓库尚未添加开源许可证。未经版权方明确许可，默认保留所有权利。
