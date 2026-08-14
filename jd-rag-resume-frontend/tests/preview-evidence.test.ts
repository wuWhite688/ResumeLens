import assert from "node:assert/strict";
import test from "node:test";
import {
  annotatePreviewEvidence,
  previewConclusion,
} from "../app/preview-evidence.ts";

test("default 0.72 keeps only the Java chunk and does not cite filtered RAG or JWT evidence", () => {
  const items = annotatePreviewEvidence(0.72);
  assert.equal(items.filter((item) => item.kept).map((item) => item.index).join(","), "0");
  const summary = previewConclusion(items, 0.72);
  assert.match(summary, /Java 后端/);
  assert.match(summary, /基于过阈证据/);
  assert.doesNotMatch(summary, /RAG 工程化/);
  assert.doesNotMatch(summary, /JWT 与数据隔离/);
});

test("preview conclusion stays silent when the threshold is unavailable or nothing is kept", () => {
  const pending = annotatePreviewEvidence(undefined);
  assert.equal(pending.every((item) => !item.kept), true);
  assert.match(previewConclusion(pending, undefined), /阈值尚未读到/);
  assert.doesNotMatch(previewConclusion(pending, undefined), /基于过阈证据/);

  const noneKept = annotatePreviewEvidence(0.9);
  assert.equal(noneKept.some((item) => item.kept), false);
  const empty = previewConclusion(noneKept, 0.9);
  assert.match(empty, /没有过阈示例块/);
  assert.doesNotMatch(empty, /Java 后端|RAG 工程化|JWT 与数据隔离/);
});
