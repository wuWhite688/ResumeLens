import type { AnalysisSummary, JobSemanticMatch } from "./lib/api";

/**
 * Keep coarse retrieval separate from expensive analysis: take the semantic Top N,
 * then omit only candidates that already have a current completed analysis.
 */
export function semanticAnalysisTargets(
  matches: JobSemanticMatch[],
  latestAnalyses: AnalysisSummary[],
  limit: number,
) {
  const latestByJob = new Map<number, AnalysisSummary>();
  for (const item of latestAnalyses) {
    const previous = latestByJob.get(item.jobDescriptionId);
    if (!previous || item.id > previous.id) latestByJob.set(item.jobDescriptionId, item);
  }
  const safeLimit = Math.max(1, Math.min(matches.length, Math.floor(limit)));
  return matches
    .slice(0, safeLimit)
    .filter((item) => latestByJob.get(item.job.id)?.status !== "COMPLETED");
}
