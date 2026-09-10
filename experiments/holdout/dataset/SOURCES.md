# Holdout v1 JD 来源记录

本文件只做来源追溯，不参与 runner 输入。`jobs/*.txt` 均未直接复制招聘页正文，而是基于公开职位页的职责/要求进行脱敏、压缩和改写；公司名、联系人、薪资与精确办公地址不进入评测文本。抓取/核对日期：2026-09-10。

| jobId | 公开来源 | URL | 使用方式 |
|---|---|---|---|
| `java-backend-order` | 后端工程师开发（实习）｜字节跳动｜牛客网 | https://www.nowcoder.com/jobs/detail/447235 | Java/Spring Boot/MySQL/Redis/MQ/订单履约等要求，已改写并去公司识别信息。 |
| `frontend-vue-admin` | 前端开发工程师（Vue3/Uni-app方向）｜智联招聘 | https://www.zhaopin.com/jobdetail/CCL1493312070J40776692616.htm | Vue3、Pinia、Vite、组件化与性能优化要求，已压缩改写。 |
| `nlp-search` | NLP算法工程师（NLP/大模型方向）｜智联招聘 | https://www.zhaopin.com/jobdetail/CC000544460J40769232516.htm | PyTorch、BERT/Transformer、算法训练与落地要求；检索部分结合岗位方向做脱敏重写。 |
| `risk-analyst` | 数据分析师（风控建模）｜智联招聘 | https://www.zhaopin.com/jobdetail/CC637006120J40719266314.htm | Python/SQL、风险指标、模型监控与跨团队落地要求，已改写。 |
| `nurse-icu` | ICU护士｜智联招聘 | https://www.zhaopin.com/jobdetail/CC431918730J40867768914.htm | 生命体征监测、呼吸机、中心静脉、动脉采血与急救护理要求，已改写。 |
| `cost-accountant` | 成本会计｜智联招聘 | https://www.zhaopin.com/jobdetail/CC158950710J40787494006.htm | 标准成本、BOM、成本差异、存货盘点与制造成本核算要求，已改写。 |
| `sre-platform` | SRE高级工程师｜领英 | https://cn.linkedin.com/jobs/view/%E9%AB%98%E7%BA%A7sre%E5%B7%A5%E7%A8%8B%E5%B8%88%EF%BC%88aws-%E8%85%BE%E8%AE%AF%E4%BA%91%EF%BC%89-at-pump-dynamics-4438475032 | Kubernetes、Prometheus/Grafana、SLO、Terraform 等可靠性要求，已改写。 |
| `security-operations` | 安全运营工程师 Tier 2｜领英 | https://cn.linkedin.com/jobs/view/%E6%88%90%E9%83%BD%EF%BC%8C%E5%AE%89%E5%85%A8%E8%BF%90%E8%90%A5%E5%B7%A5%E7%A8%8B%E5%B8%88tier-2-at-%E6%AF%95%E9%A9%AC%E5%A8%81%E4%B8%AD%E5%9B%BD-4424084725 | SIEM、事件研判、MITRE ATT&CK 与响应要求，已改写。 |
| `ux-designer-mobile` | UX Designer (Remote)｜领英 | https://cn.linkedin.com/jobs/view/ux-designer-remote-at-hire-feed-4434257991 | Figma、用户研究、可用性测试、Design System 与响应式设计要求，已改写。 |
| `product-manager-b2b` | 产品经理（AI智能体方向）｜智联招聘 | https://www.zhaopin.com/jobdetail/CC000432740J40781171016.htm | 需求访谈、PRD/原型、MVP、路线图与研发协同要求；移除具体 AI/政企品牌后改写为 B2B 通用岗位。 |
| `supply-chain-planner` | 供应链计划专家｜领英 | https://cn.linkedin.com/jobs/view/%E4%BE%9B%E5%BA%94%E9%93%BE%E8%AE%A1%E5%88%92%E4%B8%93%E5%AE%B6-at-%E6%99%AE%E5%88%A9%E5%8F%B8%E9%80%9A-4450857378 | S&OP、需求/生产计划、库存、OTIF、MRP 与报表要求，已改写。 |
| `procurement-sourcing` | 电气采购工程师｜智联招聘 | https://www.zhaopin.com/jobdetail/CC635474020J40990878502.htm | 供应商开发、询比价、合同、交期、绩效与跨团队采购协作要求，已改写。 |

说明：
- `seen_domain` 的 6 个岗位与 dev 集属于同一职能大类，但文本、公司信息与配对均为新内容。
- `new_domain` 的 6 个岗位使用 dev 集未出现的职能方向：SRE、安全运营、UX、B2B 产品、供应链计划、采购寻源。
- 简历全部为本次 holdout 新写的合成候选人材料，不含真实个人信息。
- goldPhrases 仅根据“简历原文 + 对应 JD”人工确定；在冻结本数据集前未查看任何 holdout 相似度、检索结果或 runner 输出。
