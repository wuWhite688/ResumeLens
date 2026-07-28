# jd-rag-resume-backend

Spring Boot backend for **ResumeLens / JD-RAG**：JWT 鉴权、简历与 JD CRUD、本地 GTE 向量检索（Lucene）、异步 AI 匹配分析。

完整作品集说明、架构图与联调步骤见上级目录：

→ [../README.md](../README.md)

## 快速命令

```powershell
# 测试
.\mvnw.cmd test

# 打包
.\mvnw.cmd clean package -DskipTests

# 后台启动（默认 :8080，会检查/下载本地 embedding 模型）
.\start-backend-background.ps1
```

## 环境变量

| 变量 | 说明 |
|------|------|
| `AI_API_KEY` | LLM API Key |
| `AI_BASE_URL` | OpenAI 兼容基址或完整 `/chat/completions` URL |
| `AI_MODEL` | 模型名 |
| `AI_TIMEOUT_SECONDS` | 超时，默认 60 |
| `AI_MOCK_ENABLED` | `true` 时不调用真实 LLM |
| `RUN_EMBEDDING_REGRESSION` | `true` 时运行真实 ONNX 回归测试 |
| `JWT_EXPIRATION_MINUTES` | access token 有效期，默认 15 分钟 |
| `REFRESH_TOKEN_EXPIRATION_DAYS` | refresh token 有效期，默认 7 天 |
| `REFRESH_COOKIE_SECURE` | HTTPS 部署时设为 `true`；本地 HTTP 默认为 `false` |

本地数据库与 JWT 默认值见 `src/main/resources/application.properties`（仅开发用）。

refresh token 仅以 SHA-256 哈希写入数据库，每次续期都会轮换；已撤销 token 再次出现时会撤销同一 token family，用于阻断重放。
