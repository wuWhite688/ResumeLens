import assert from "node:assert/strict";
import test from "node:test";

import { analysisPollTimeoutMs, pollAnalysisUntilSettled } from "../app/analysis-poll.ts";
import type { Analysis } from "../app/lib/api.ts";

function analysis(status: Analysis["status"], id = 1): Analysis {
  return {
    id,
    resumeId: 1,
    resumeTitle: "resume",
    jobDescriptionId: 2,
    jobTitle: "job",
    matchScore: status === "COMPLETED" ? 80 : null,
    status,
    createdAt: "2026-08-20T00:00:00",
  };
}

test("poll timeout follows pendingTimeoutMinutes and is at least one minute", () => {
  assert.equal(analysisPollTimeoutMs(10), 600_000);
  assert.equal(analysisPollTimeoutMs(undefined), 600_000);
  assert.equal(analysisPollTimeoutMs(0), 600_000);
});

test("pollAnalysisUntilSettled keeps waiting past 60 seconds until completed", async () => {
  let clock = 0;
  let calls = 0;
  const result = await pollAnalysisUntilSettled(analysis("PENDING"), {
    timeoutMs: 600_000,
    now: () => clock,
    sleep: async () => {
      clock += 30_000;
    },
    fetchById: async () => {
      calls += 1;
      return analysis(calls >= 3 ? "COMPLETED" : "PENDING");
    },
  });
  assert.equal(result.timedOut, false);
  assert.equal(result.analysis.status, "COMPLETED");
  assert.ok(clock >= 60_000);
  assert.equal(calls, 3);
});

test("pollAnalysisUntilSettled reports timeout without throwing", async () => {
  let clock = 0;
  const result = await pollAnalysisUntilSettled(analysis("PENDING"), {
    timeoutMs: 3_000,
    now: () => clock,
    sleep: async (ms) => {
      clock += ms;
    },
    fetchById: async () => analysis("PENDING"),
  });
  assert.equal(result.timedOut, true);
  assert.equal(result.analysis.status, "PENDING");
});
