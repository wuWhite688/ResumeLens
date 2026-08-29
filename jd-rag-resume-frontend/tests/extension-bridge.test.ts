import assert from "node:assert/strict";
import test from "node:test";

import {
  EXTENSION_BRIDGE_CHANNEL,
  dispatchExtensionBridgeRequest,
  isExtensionBridgeRequest,
  type ExtensionBridgeRequest,
} from "../app/extension-bridge-core.ts";

function request(
  action: ExtensionBridgeRequest["action"],
  payload?: unknown,
): ExtensionBridgeRequest {
  return {
    channel: EXTENSION_BRIDGE_CHANNEL,
    direction: "request",
    requestId: "request-1",
    action,
    payload,
  };
}

test("recognizes only bounded bridge requests", () => {
  assert.equal(isExtensionBridgeRequest(request("prepare", {})), true);
  assert.equal(isExtensionBridgeRequest({ ...request("prepare", {}), direction: "response" }), false);
  assert.equal(isExtensionBridgeRequest({ ...request("prepare", {}), requestId: "x".repeat(101) }), false);
  assert.equal(isExtensionBridgeRequest({ ...request("prepare", {}), action: "deleteEverything" }), false);
});

test("prepare loads resume summaries and source duplicate state", async () => {
  const calls: string[] = [];
  const result = await dispatchExtensionBridgeRequest(
    request("prepare", { sourcePlatform: "BOSS", sourceJobId: "abc-123" }),
    (async (path: string) => {
      calls.push(path);
      if (path.startsWith("/api/resumes")) {
        return {
          content: [{
            id: 7,
            title: "Java 简历",
            candidateName: "Arthur",
            phone: "should-not-cross-the-bridge",
            email: "private@example.com",
            rawText: "private resume text",
          }],
        };
      }
      return {
        found: true,
        job: { id: 9, title: "Java 后端", description: "full private job text" },
      };
    }) as never,
  ) as { resumes: Array<{ id: number }>; existingJob: { id: number } };

  assert.equal(result.resumes[0].id, 7);
  assert.equal(result.existingJob.id, 9);
  assert.deepEqual(result.resumes[0], { id: 7, title: "Java 简历", candidateName: "Arthur" });
  assert.deepEqual(result.existingJob, { id: 9, title: "Java 后端" });
  assert.deepEqual(calls, [
    "/api/resumes?size=50",
    "/api/job-descriptions/source?sourcePlatform=BOSS&sourceJobId=abc-123",
  ]);
});

test("analysis responses omit retrieved context and account details", async () => {
  const result = await dispatchExtensionBridgeRequest(
    request("getAnalysis", { id: 12 }),
    (async () => ({
      id: 12,
      userId: 1,
      username: "arthur",
      resumeId: 7,
      resumeTitle: "Java 简历",
      jobDescriptionId: 9,
      jobTitle: "Java 后端",
      matchScore: 86,
      status: "COMPLETED",
      summary: "summary",
      retrievedContext: "raw resume evidence",
      strengths: "[]",
      missingSkills: "[]",
      improvementSuggestions: "[]",
      interviewQuestions: "[]",
      createdAt: "2026-08-28T10:00:00",
    })) as never,
  ) as Record<string, unknown>;

  assert.equal(result.id, 12);
  assert.equal("retrievedContext" in result, false);
  assert.equal("username" in result, false);
  assert.equal("resumeTitle" in result, false);
  assert.equal("jobTitle" in result, false);
});

test("analyze forwards the editable captured job as JSON", async () => {
  let capturedPath = "";
  let capturedInit: RequestInit | undefined;
  const payload = {
    resumeId: 7,
    forceReanalyze: false,
    job: {
      title: "Java 后端",
      companyName: "示例公司",
      description: "Spring Boot",
      sourcePlatform: "BOSS",
      sourceUrl: "https://www.zhipin.com/job_detail/abc.html",
      sourceJobId: "abc",
    },
  };

  const result = await dispatchExtensionBridgeRequest(
    request("analyze", payload),
    (async (path: string, init?: RequestInit) => {
      capturedPath = path;
      capturedInit = init;
      return {
        job: { id: 9, title: "Java 后端", description: "private" },
        analysis: {
          id: 12,
          resumeId: 7,
          jobDescriptionId: 9,
          status: "PENDING",
          retrievedContext: "private",
        },
        existingJob: false,
        contentChanged: false,
        reusedAnalysis: false,
      };
    }) as never,
  ) as { job: Record<string, unknown>; analysis: Record<string, unknown> };

  assert.equal(capturedPath, "/api/browser-extension/analyze");
  assert.equal(capturedInit?.method, "POST");
  assert.deepEqual(JSON.parse(String(capturedInit?.body)), payload);
  assert.deepEqual(result.job, { id: 9, title: "Java 后端" });
  assert.equal("retrievedContext" in result.analysis, false);
});

test("latestAnalysis still reads GET /api/analysis-histories/latest", async () => {
  let capturedPath = "";
  await dispatchExtensionBridgeRequest(
    request("latestAnalysis", { resumeId: 7, jobDescriptionId: 11 }),
    (async (path: string) => {
      capturedPath = path;
      return { id: 88, status: "COMPLETED" };
    }) as never,
  );
  assert.equal(capturedPath, "/api/analysis-histories/latest?resumeId=7&jobDescriptionId=11");
});

test("getAnalysis still reads GET /api/analysis-histories/{id}", async () => {
  let capturedPath = "";
  await dispatchExtensionBridgeRequest(
    request("getAnalysis", { id: 88 }),
    (async (path: string) => {
      capturedPath = path;
      return { id: 88, status: "PENDING" };
    }) as never,
  );
  assert.equal(capturedPath, "/api/analysis-histories/88");
});

test("rejects malformed source identities before an API call", async () => {
  await assert.rejects(
    dispatchExtensionBridgeRequest(
      request("prepare", { sourcePlatform: "", sourceJobId: "abc" }),
      (async () => null) as never,
    ),
    /sourcePlatform/,
  );
});
