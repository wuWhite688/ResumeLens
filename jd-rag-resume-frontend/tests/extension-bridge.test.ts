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
      if (path.startsWith("/api/resumes")) return { content: [{ id: 7, title: "Java 简历" }] };
      return { found: true, job: { id: 9, title: "Java 后端" } };
    }) as never,
  ) as { resumes: Array<{ id: number }>; existingJob: { id: number } };

  assert.equal(result.resumes[0].id, 7);
  assert.equal(result.existingJob.id, 9);
  assert.deepEqual(calls, [
    "/api/resumes?size=50",
    "/api/job-descriptions/source?sourcePlatform=BOSS&sourceJobId=abc-123",
  ]);
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

  await dispatchExtensionBridgeRequest(
    request("analyze", payload),
    (async (path: string, init?: RequestInit) => {
      capturedPath = path;
      capturedInit = init;
      return {};
    }) as never,
  );

  assert.equal(capturedPath, "/api/browser-extension/analyze");
  assert.equal(capturedInit?.method, "POST");
  assert.deepEqual(JSON.parse(String(capturedInit?.body)), payload);
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
