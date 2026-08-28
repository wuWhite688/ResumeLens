# ResumeLens 岗位助手（Chrome MV3）

第一版支持在 BOSS 直聘职位页抓取 JD、手动校正、选择 ResumeLens 简历、启动 RAG 匹配分析，并在扩展里显示结果。岗位会写入 ResumeLens 的个人岗位库；相同 BOSS 岗位再次出现时按来源 ID 查重，并优先复用同一份简历的已有分析。

## 本地安装

1. 启动 ResumeLens 后端（8080）和前端（3000），在 `http://localhost:3000` 登录并至少保存一份简历。
2. 打开 `chrome://extensions`，开启“开发者模式”，选择“加载已解压的扩展程序”。
3. 选择本目录 `browser-extension/`。
4. 打开一个 BOSS 直聘职位详情页，点击工具栏里的“ResumeLens 岗位助手”。

扩展不会保存账号密码或刷新令牌。它通过已登录的 ResumeLens 网页调用同源 BFF，因此使用时需要保留一个已登录的 ResumeLens 标签页。扩展只长期拥有本机 ResumeLens 地址的访问权限；BOSS 页面权限通过 `activeTab` 在点击扩展时临时授予，不会在后台常驻读取 BOSS 页面。

BOSS 页面结构变动时，扩展会保留可编辑表单并提示缺失字段；不要在字段不完整时直接提交。若页面没有稳定岗位 ID，扩展会根据完整岗位内容生成兜底指纹，岗位内容变化后会被视为新岗位，避免把两个相似职位错误合并。

## 验证

```powershell
npm test
npm run pack
```

`npm run pack` 只把 `manifest.json` 和运行所需的 `src/` 打进 `dist/resumelens-browser-extension-v<版本>.zip`，可解压后按上面的步骤安装。测试覆盖 BOSS 岗位 ID、规范化 URL、字段提取、最小权限和弹窗请求失效保护。完整端到端验证还需要真实登录态、BOSS 页面、MySQL、Embedding 模型与生成模型同时可用。
