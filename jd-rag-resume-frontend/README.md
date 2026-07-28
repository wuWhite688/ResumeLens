# jd-rag-resume-frontend · ResumeLens

简历 / JD 智能匹配前端工作台：登录、简历与职位 CRUD、Hybrid RAG 分析报告、Markdown / PDF 导出。

完整项目说明见上级目录：

→ [../README.md](../README.md)

## 开发

```powershell
npm install
# 后端默认 http://127.0.0.1:8080
$env:BACKEND_API_URL = "http://127.0.0.1:8080"
npm run dev
```

请求经 `app/api/backend/[...path]` 代理到 Spring Boot，浏览器侧统一前缀 `/api/backend`。

认证使用短时 access token + 可轮换 refresh token：access token 只保存在页面内存中，refresh token 由后端写入 `HttpOnly`、`SameSite=Lax` Cookie。接口返回 401 时，`app/lib/api.ts` 会单次静默续期并重放原请求；续期失败则清理会话并返回登录页。

## 脚本

| 命令 | 说明 |
|------|------|
| `npm run dev` | 本地开发 |
| `npm run build` | 生产构建 |
| `npm test` | 报告导出单测 + 构建后的 SSR 壳层测试 |
| `npm run lint` | ESLint |

## 关键模块

- `app/page.tsx` — 主工作台（认证、CRUD、分析、导出）
- `app/lib/api.ts` — 统一 API 客户端、401 恢复与静默续期
- `app/report-export.ts` — 匹配报告 Markdown / 打印 PDF HTML
