import assert from "node:assert/strict";
import test from "node:test";
import {
  asList,
  buildReportMarkdown,
  buildReportPrintHtml,
  evidenceChunks,
  openPrintableReport,
  parseRagMeta,
  reportFilename,
} from "../app/report-export.ts";

const sampleContext = `# rag-meta kept=2 filtered=1 avgSimilarity=0.7100 minSimilarity=0.55 topK=5 hybrid=true dualQuery=true

[resume-chunk-0 | similarity=0.7800 | raw=0.7600 | status=kept | section=技能 | boost=Java]
熟练掌握 Java、Spring Boot。

[resume-chunk-5 | similarity=0.2100 | raw=0.2100 | status=below-threshold | section=其他 | boost=-]
兴趣爱好：篮球。
`;

const analysis = {
  id: 42,
  resumeTitle: "Java 后端开发简历",
  jobTitle: "RAG 平台工程师",
  matchScore: 82.5,
  status: "COMPLETED",
  summary: "整体匹配较好。",
  strengths: '["Java 后端对齐 [chunk-0]","有 RAG 经验"]',
  missingSkills: "高并发调优",
  improvementSuggestions: "补充量化指标",
  interviewQuestions: '["如何设计分块策略？"]',
  retrievedContext: sampleContext,
  createdAt: "2026-07-12T06:01:00.000Z",
};

test("asList parses JSON arrays and plain text", () => {
  assert.deepEqual(asList('["a","b"]'), ["a", "b"]);
  assert.deepEqual(asList("x；y"), ["x", "y"]);
});

test("parseRagMeta and evidenceChunks read retrieval payload", () => {
  const meta = parseRagMeta(sampleContext);
  assert.ok(meta);
  assert.equal(meta!.kept, 2);
  assert.equal(meta!.hybrid, true);
  const chunks = evidenceChunks(sampleContext);
  assert.equal(chunks.length, 2);
  assert.equal(chunks[0].kept, true);
  assert.equal(chunks[1].kept, false);
});

test("buildReportMarkdown includes score, sections, and evidence", () => {
  const md = buildReportMarkdown(analysis);
  assert.match(md, /# 匹配分析报告 #42/);
  assert.match(md, /82\.50/);
  assert.match(md, /核心优势/);
  assert.match(md, /Chunk 0/);
  assert.match(md, /进入 prompt/);
  assert.match(md, /兴趣爱好：篮球/);
});

test("buildReportPrintHtml is printable HTML with escaped content", () => {
  const html = buildReportPrintHtml({
    ...analysis,
    summary: '含 <script>alert(1)</script> 摘要',
  });
  assert.match(html, /<!DOCTYPE html>/);
  assert.match(html, /匹配分析报告 #42/);
  assert.match(html, /&lt;script&gt;/);
  assert.doesNotMatch(html, /<script>alert\(1\)<\/script>/);
  assert.match(html, /window\.print/);
});

test("reportFilename is filesystem-safe", () => {
  const name = reportFilename(analysis, "md");
  assert.match(name, /^resume-match-42-.*\.md$/);
  assert.doesNotMatch(name, /[\\/:*?"<>|]/);
});

test("openPrintableReport keeps popup handle and severs opener", () => {
  const writes: string[] = [];
  let requestedFeatures = "";
  const popup = {
    opener: {} as unknown,
    document: {
      open() {},
      write(value: string) {
        writes.push(value);
      },
      close() {},
    },
  };
  const originalWindow = Object.getOwnPropertyDescriptor(globalThis, "window");

  Object.defineProperty(globalThis, "window", {
    configurable: true,
    value: {
      open(_url: string, _target: string, features: string) {
        requestedFeatures = features;
        return popup;
      },
    },
  });

  try {
    openPrintableReport("<p>print me</p>");
  } finally {
    if (originalWindow) {
      Object.defineProperty(globalThis, "window", originalWindow);
    } else {
      delete (globalThis as typeof globalThis & { window?: unknown }).window;
    }
  }

  assert.equal(requestedFeatures, "width=920,height=800");
  assert.equal(popup.opener, null);
  assert.deepEqual(writes, ["<p>print me</p>"]);
});
