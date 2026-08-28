import type { Analysis, AnalysisSummary } from "./lib/api";

export type AnalysisPollResult = {
  analysis: Analysis;
  timedOut: boolean;
  cancelled: boolean;
};

export function analysisPollTimeoutMs(pendingTimeoutMinutes?: number) {
  const minutes = Number.isFinite(pendingTimeoutMinutes) && (pendingTimeoutMinutes ?? 0) > 0
    ? Number(pendingTimeoutMinutes)
    : 10;
  return Math.max(60_000, minutes * 60_000);
}

export function mergeLatestAnalysisSummaries(
  remote: AnalysisSummary[],
  local: AnalysisSummary[],
) {
  const latest = new Map<string, AnalysisSummary>();
  for (const item of [...remote, ...local]) {
    const key = `${item.resumeId}:${item.jobDescriptionId}`;
    const previous = latest.get(key);
    if (!previous
        || item.id > previous.id
        || (item.id === previous.id && previous.status === "PENDING" && item.status !== "PENDING")) {
      latest.set(key, item);
    }
  }
  return [...latest.values()];
}

export async function pollAnalysisUntilSettled(
  initial: Analysis,
  options: {
    fetchById: (id: number) => Promise<Analysis>;
    timeoutMs: number;
    now?: () => number;
    sleep?: (ms: number) => Promise<void>;
    onProgress?: (current: Analysis) => void;
    shouldContinue?: () => boolean;
  },
): Promise<AnalysisPollResult> {
  let current = initial;
  options.onProgress?.(current);
  const now = options.now ?? Date.now;
  const sleep = options.sleep ?? ((ms: number) => new Promise((resolve) => setTimeout(resolve, ms)));
  const deadline = now() + options.timeoutMs;

  while (current.status === "PENDING" && now() < deadline) {
    if (options.shouldContinue && !options.shouldContinue()) {
      return { analysis: current, timedOut: false, cancelled: true };
    }
    await sleep(pollIntervalMs(deadline - now()));
    if (options.shouldContinue && !options.shouldContinue()) {
      return { analysis: current, timedOut: false, cancelled: true };
    }
    current = await options.fetchById(current.id);
    options.onProgress?.(current);
  }

  return {
    analysis: current,
    timedOut: current.status === "PENDING",
    cancelled: false,
  };
}

function pollIntervalMs(remainingMs: number) {
  if (remainingMs > 5 * 60_000) return 2_000;
  if (remainingMs > 60_000) return 1_500;
  return 1_000;
}
