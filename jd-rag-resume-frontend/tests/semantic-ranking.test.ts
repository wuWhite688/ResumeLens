import assert from "node:assert/strict";
import test from "node:test";

import type { AnalysisSummary, JobSemanticMatch } from "../app/lib/api.ts";
import { semanticAnalysisTargets } from "../app/semantic-ranking.ts";

const matches = [1, 2, 3].map((id, index) => ({
  job: {
    id,
    title: `Job ${id}`,
    companyName: "Example",
    description: "Backend",
  },
  similarity: 0.9 - index * 0.1,
})) satisfies JobSemanticMatch[];

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
