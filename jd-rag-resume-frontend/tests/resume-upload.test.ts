import assert from "node:assert/strict";
import test from "node:test";

import { appendResumeUploadFields, prepareResumeUploadDraft } from "../app/resume-upload.ts";

test("selecting a PDF clears sample rawText so the backend extracts the uploaded file", () => {
  const draft = prepareResumeUploadDraft({
    title: "Java 后端开发简历",
    candidateName: "张三",
    phone: "13800000000",
    email: "zhangsan@example.com",
    rawText: "示例简历文本",
  }, "arthur-resume.pdf");

  assert.equal(draft.rawText, "");

  const form = new FormData();
  appendResumeUploadFields(form, draft);
  assert.equal(form.has("rawText"), false);
});

test("selecting a file derives a title only when the title is empty", () => {
  const draft = prepareResumeUploadDraft({
    title: "",
    candidateName: "Arthur",
    phone: "",
    email: "",
    rawText: "stale text",
  }, "arthur-resume.pdf");

  assert.equal(draft.title, "arthur-resume");
  assert.equal(draft.candidateName, "Arthur");
});

test("file upload serialization ignores stale rawText even if async state races", () => {
  const form = new FormData();
  appendResumeUploadFields(form, {
    title: "Arthur Resume",
    candidateName: "Arthur",
    phone: "",
    email: "arthur@example.com",
    rawText: "stale text from a previously selected txt file",
  });

  assert.equal(form.get("title"), "Arthur Resume");
  assert.equal(form.get("candidateName"), "Arthur");
  assert.equal(form.get("email"), "arthur@example.com");
  assert.equal(form.has("rawText"), false);
});
