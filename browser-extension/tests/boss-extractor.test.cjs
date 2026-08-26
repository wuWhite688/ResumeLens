const assert = require("node:assert/strict");
const test = require("node:test");

const extractor = require("../src/boss-extractor.js");

function node(text, attributes = {}) {
  return {
    innerText: text,
    textContent: text,
    getAttribute(name) {
      return attributes[name] || null;
    },
  };
}

function fixture(single = {}, multiple = {}, title = "") {
  return {
    title,
    querySelector(selector) {
      return single[selector] || null;
    },
    querySelectorAll(selector) {
      return multiple[selector] || (single[selector] ? [single[selector]] : []);
    },
  };
}

test("extracts a BOSS detail page and keeps a stable source identity", () => {
  const documentRef = fixture(
    {
      ".job-detail-header h1": node(" Java 后端工程师 "),
      ".company-info-box .company-name": node("示例科技"),
      ".job-address .location-address": node("杭州 · 西湖区"),
      ".job-card-wrapper.active a[href*='/job_detail/']": node("", { href: "/job_detail/abcDEF123.html?securityId=volatile" }),
    },
    {
      ".job-detail-section .job-sec-text": [node("负责 Spring Boot 服务开发。\n参与 MySQL 性能优化和接口设计。")],
      ".job-keyword-list li": [node("Java"), node("Spring Boot"), node("Java")],
    },
  );

  const result = extractor.extract(documentRef, "https://www.zhipin.com/web/geek/job");
  assert.equal(result.job.title, "Java 后端工程师");
  assert.equal(result.job.companyName, "示例科技");
  assert.equal(result.job.sourceJobId, "abcDEF123");
  assert.equal(result.job.sourceUrl, "https://www.zhipin.com/job_detail/abcDEF123.html");
  assert.equal(result.job.requirements, "Java、Spring Boot");
  assert.deepEqual(result.warnings, []);
});

test("source id falls back deterministically when BOSS exposes no detail id", () => {
  const first = extractor.stableHash("same job");
  const second = extractor.stableHash("same job");
  assert.equal(first, second);
  assert.match(first, /^[0-9a-f]{16}$/);
});

test("canonical URLs discard tracking query and fragments", () => {
  assert.equal(
    extractor.canonicalizeUrl(
      "https://www.zhipin.com/job_detail/key.html?ka=search_list_1#detail",
      "https://www.zhipin.com",
    ),
    "https://www.zhipin.com/job_detail/key.html",
  );
});

test("missing fields produce editable warnings instead of invented data", () => {
  const result = extractor.extract(
    fixture({}, { ".job-detail-section .job-sec-text": [node("太短")] }, "招聘_BOSS直聘"),
    "https://www.zhipin.com/web/geek/job",
  );
  assert.equal(result.job.companyName, "");
  assert.ok(result.warnings.some((item) => item.includes("公司")));
  assert.ok(result.warnings.some((item) => item.includes("正文过短")));
});
