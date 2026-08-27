import assert from "node:assert/strict";
import test from "node:test";

import {
  coverRequirements,
  coverageSummary,
  extractTerms,
  splitRequirements,
  type CoverageSource,
} from "../app/requirement-coverage.ts";

/** 取自 experiments/threshold-sweep/dataset 的真实素材，保证阈值是对着真数据调的。 */
const JAVA_JD = [
  "1. 本科及以上，计算机相关，3 年及以上 Java 后端经验。",
  "2. 精通 Java，熟悉并发、线程池与常见线上问题排查。",
  "3. 熟练使用 Spring Boot、Spring MVC、MyBatis 或 JPA。",
  "4. 熟悉 MySQL 索引与事务，有过慢 SQL 优化案例。",
  "5. 熟悉 Redis（缓存、分布式锁）以及 Kafka 或同等消息队列。",
].join("\n");

const RESUME_CHUNKS: CoverageSource[] = [
  {
    index: 0,
    kept: true,
    content: [
      "求职意向：Java 后端开发工程师 / 支付与交易中台。工作年限：5 年。",
      "熟悉 Java 17 与并发编程，能够定位线程池拒绝、死锁与可见性问题。",
      "熟练使用 Spring Boot、Spring MVC、Spring Data JPA 与 MyBatis 构建 REST API。",
      "熟悉 MySQL 索引设计、执行计划、慢查询治理，以及 Redis 缓存、分布式锁与限流。",
      "有 Kafka 异步解耦、消息幂等与死信处理经验；了解 RabbitMQ。",
    ].join("\n"),
  },
  {
    index: 1,
    kept: true,
    content: "职责：设计对账领域模型，实现文件拉取、解析、勾兑与差错工单。结果：T+1 对账自动化率提升到 96%。",
  },
];

test("splitRequirements strips list markers", () => {
  const rows = splitRequirements([
    "1. 本科及以上，3 年及以上 Java 后端经验",
    "2) 慢查询从 1.8s 降到 40ms",
    "- 5000 QPS 峰值下稳定运行",
    "3、支持 2 个机房容灾",
  ].join("\n"));

  assert.deepEqual(rows.map((row) => row.text), [
    "本科及以上，3 年及以上 Java 后端经验",
    "慢查询从 1.8s 降到 40ms",
    "5000 QPS 峰值下稳定运行",
    "支持 2 个机房容灾",
  ]);
  assert.deepEqual(rows.map((row) => row.no), [1, 2, 3, 4]);
});

/**
 * 序号剥离必须只吃序号。危险的是「数字 + 点 + 数字」开头的正文——
 * 少了守卫，"99.9% 可用性" 会被削成 "9% 可用性"，而指标正是 JD 里最不能丢的部分。
 */
test("splitRequirements does not eat numbers that open the requirement itself", () => {
  const rows = splitRequirements([
    "99.9% 可用性保障经验",
    "1.8s 降到 40ms 的慢查询优化案例",
    "7*24 值班轮转",
  ].join("\n"));

  assert.deepEqual(rows.map((row) => row.text), [
    "99.9% 可用性保障经验",
    "1.8s 降到 40ms 的慢查询优化案例",
    "7*24 值班轮转",
  ]);
});

test("splitRequirements falls back to semicolons when the JD is a single line", () => {
  const rows = splitRequirements("Java、Spring Boot、MySQL；了解 JWT 与 REST。");
  assert.deepEqual(rows.map((row) => row.text), ["Java、Spring Boot、MySQL", "了解 JWT 与 REST。"]);
});

test("splitRequirements treats blank input as no requirements", () => {
  assert.deepEqual(splitRequirements(undefined), []);
  assert.deepEqual(splitRequirements("   \n  "), []);
});

test("extractTerms drops filler words that both sides always contain", () => {
  const terms = extractTerms("熟悉 Spring Boot，具备良好的沟通能力");
  assert.ok(terms.latin.includes("spring"));
  assert.ok(terms.latin.includes("boot"));
  assert.ok(!terms.cjk.includes("熟悉"), "熟悉 是停用词");
  assert.ok(!terms.cjk.includes("能力"), "能力 是停用词");
  assert.ok(terms.cjk.includes("沟通"), "沟通 有区分度，不该被停用");
});

test("matching JD requirements resolve to the chunks that support them", () => {
  const rows = coverRequirements(JAVA_JD, RESUME_CHUNKS);

  assert.equal(rows.length, 5);
  assert.deepEqual(coverageSummary(rows), { covered: 5, total: 5 });
  for (const row of rows) {
    assert.ok(row.chunks.includes(0), `第 ${row.no} 条应由 chunk-0 支撑，实际 ${JSON.stringify(row.chunks)}`);
  }
  assert.ok(rows[2].terms.includes("mybatis"), "第 3 条应命中 MyBatis");
});

/** 红线：换一份不相干的 JD 必须大面积落空，否则这个面板只是在给每条要求盖橡皮图章。 */
test("a JD from another discipline is mostly not covered by the same resume", () => {
  const frontendJd = [
    "1. 精通 React 与 TypeScript，熟悉 Hooks 与状态管理。",
    "2. 熟悉浏览器渲染、首屏性能与埋点体系。",
    "3. 有组件库沉淀或可视化搭建经验优先。",
    "4. 熟悉 Webpack、Vite 等构建工具链。",
  ].join("\n");

  const rows = coverRequirements(frontendJd, RESUME_CHUNKS);
  const { covered } = coverageSummary(rows);
  assert.equal(covered, 0, `跨领域 JD 不应被判为覆盖，实际命中 ${covered} 条`);
});

test("requirements are only matched against chunks that entered the prompt", () => {
  const filtered = RESUME_CHUNKS.map((chunk) => ({ ...chunk, kept: false }));
  const rows = coverRequirements(JAVA_JD, filtered);

  assert.equal(rows.length, 5);
  assert.deepEqual(coverageSummary(rows), { covered: 0, total: 5 });
});

test("a requirement made only of filler words stays uncovered", () => {
  const rows = coverRequirements("1. 具备良好的学习能力，能够熟悉相关工作。", RESUME_CHUNKS);
  assert.equal(rows[0].covered, false);
  assert.deepEqual(rows[0].chunks, []);
});
