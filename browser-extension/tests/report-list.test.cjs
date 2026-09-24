const assert = require("node:assert/strict");
const test = require("node:test");

const { asList } = require("../src/report-list.js");

test("parses JSON arrays and splits plain text", () => {
  assert.deepEqual(asList('["a","b"]'), ["a", "b"]);
  assert.deepEqual(asList("x；y\nz"), ["x", "y", "z"]);
  assert.deepEqual(asList(""), []);
});

test("keeps numbers that belong to the content", () => {
  // 与前端 app/report-export.ts 的 asList 同一规则：简历里的量化数字不能被当成序号削掉。
  assert.deepEqual(
    asList("5 年 Java 后端经验\n5000 QPS 峰值\n1.8s 慢查询优化到 40ms\n99.9% 可用性"),
    ["5 年 Java 后端经验", "5000 QPS 峰值", "1.8s 慢查询优化到 40ms", "99.9% 可用性"],
  );
});

test("strips bullets and ordinals", () => {
  assert.deepEqual(
    asList("1. 熟悉 Redis\n2) Kafka\n3、Docker\n- Git\n• Linux\n* Vue\n10.Elasticsearch"),
    ["熟悉 Redis", "Kafka", "Docker", "Git", "Linux", "Vue", "Elasticsearch"],
  );
  assert.deepEqual(asList("- 1. 熟悉 Redis"), ["熟悉 Redis"]);
});
