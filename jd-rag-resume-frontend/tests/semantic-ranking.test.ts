import assert from "node:assert/strict";
import test from "node:test";

import { ApiError, type AnalysisSummary, type JobSemanticMatch } from "../app/lib/api.ts";
import {
  DEFAULT_JOB_SORT,
  requestSemanticMatches,
  semanticAnalysisTargets,
} from "../app/semantic-ranking.ts";

const matches = [1, 2, 3].map((id, index) => ({
  job: {
    id,
    title: `Job ${id}`,
    companyName: "Example",
    description: "Backend",
  },
  similarity: 0.9 - index * 0.1,
})) satisfies JobSemanticMatch[];

test("semantic coarse ranking is the default job-library order", () => {
  assert.equal(DEFAULT_JOB_SORT, "semantic");
});

test("current embeddings need only the read-only matches request", async () => {
  const calls: Array<{ path: string; method: string }> = [];
  const request = async <T>(path: string, init: RequestInit = {}) => {
    calls.push({ path, method: init.method || "GET" });
    return matches as T;
  };

  assert.equal(await requestSemanticMatches(7, request), matches);
  assert.deepEqual(calls, [
    { path: "/api/job-descriptions/matches?resumeId=7&limit=200", method: "GET" },
  ]);
});

test("stale embeddings use an explicit POST refresh before one GET retry", async () => {
  const calls: Array<{ path: string; method: string }> = [];
  let firstRead = true;
  const request = async <T>(path: string, init: RequestInit = {}) => {
    calls.push({ path, method: init.method || "GET" });
    if (firstRead) {
      firstRead = false;
      throw new ApiError("SEMANTIC_EMBEDDING_STALE", "refresh required", 409);
    }
    return (init.method === "POST" ? undefined : matches) as T;
  };

  assert.equal(await requestSemanticMatches(7, request), matches);
  assert.deepEqual(calls, [
    { path: "/api/job-descriptions/matches?resumeId=7&limit=200", method: "GET" },
    { path: "/api/job-descriptions/matches/refresh?resumeId=7", method: "POST" },
    { path: "/api/job-descriptions/matches?resumeId=7&limit=200", method: "GET" },
  ]);
});

test("Top N keeps semantic order and skips current completed analyses", () => {
  const analyses = [
    { id: 9, resumeId: 7, jobDescriptionId: 1, matchScore: 88, status: "COMPLETED", createdAt: "2026-08-29T00:00:00Z" },
    { id: 10, resumeId: 7, jobDescriptionId: 2, matchScore: null, status: "PENDING", createdAt: "2026-08-29T00:01:00Z" },
  ] satisfies AnalysisSummary[];

  assert.deepEqual(semanticAnalysisTargets(matches, analyses, 3).map((item) => item.job.id), [2, 3]);
});

test("Top N boundary is applied before completed candidates are omitted", () => {
  const analyses = [
    { id: 9, resumeId: 7, jobDescriptionId: 1, matchScore: 88, status: "COMPLETED", createdAt: "2026-08-29T00:00:00Z" },
  ] satisfies AnalysisSummary[];

  assert.deepEqual(semanticAnalysisTargets(matches, analyses, 1), []);
});
