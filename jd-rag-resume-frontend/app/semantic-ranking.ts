import type { AnalysisSummary, ApiError, JobSemanticMatch } from "./lib/api";

export const DEFAULT_JOB_SORT = "semantic" as const;
const STALE_EMBEDDING_CODE = "SEMANTIC_EMBEDDING_STALE";

type ApiRequester = <T>(path: string, init?: RequestInit) => Promise<T>;

/**
 * Keep the GET endpoint read-only. Only stale derived data takes the explicit
 * POST refresh path, after which the ranking read is retried once.
 */
export async function requestSemanticMatches(
  resumeId: number,
  request: ApiRequester,
): Promise<JobSemanticMatch[]> {
  const matchesPath = `/api/job-descriptions/matches?resumeId=${resumeId}&limit=200`;
  try {
    return await request<JobSemanticMatch[]>(matchesPath);
  } catch (reason) {
    if (!hasApiErrorCode(reason, STALE_EMBEDDING_CODE)) throw reason;
    await request<void>(
      `/api/job-descriptions/matches/refresh?resumeId=${resumeId}`,
      { method: "POST" },
    );
    return request<JobSemanticMatch[]>(matchesPath);
  }
}

function hasApiErrorCode(reason: unknown, code: string): reason is ApiError {
  return reason instanceof Error
    && "code" in reason
    && (reason as { code?: unknown }).code === code;
}

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
