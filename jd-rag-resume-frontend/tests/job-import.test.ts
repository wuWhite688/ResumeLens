import assert from "node:assert/strict";
import test from "node:test";
import { parseJobImportPayload, SAMPLE_BULK_JOBS } from "../app/lib/api.ts";

test("parseJobImportPayload accepts JSON array", () => {
  const items = parseJobImportPayload(JSON.stringify(SAMPLE_BULK_JOBS));
  assert.equal(items.length, 2);
  assert.equal(items[0].title, "Java 后端工程师");
  assert.ok(items[0].description.includes("招聘业务"));
});

test("parseJobImportPayload accepts { items: [...] }", () => {
  const items = parseJobImportPayload(JSON.stringify({ items: SAMPLE_BULK_JOBS }));
  assert.equal(items.length, 2);
});

test("parseJobImportPayload accepts NDJSON", () => {
  const ndjson = SAMPLE_BULK_JOBS.map((item) => JSON.stringify(item)).join("\n");
  const items = parseJobImportPayload(ndjson);
  assert.equal(items.length, 2);
});

test("parseJobImportPayload rejects missing required fields", () => {
  assert.throws(() => parseJobImportPayload(JSON.stringify([{ title: "x" }])), /title、companyName、description/);
});
