import type { Analysis } from "./lib/api";

export type AnalysisPollResult = {
  analysis: Analysis;
  timedOut: boolean;
};

export function analysisPollTimeoutMs(pendingTimeoutMinutes?: number) {
  const minutes = Number.isFinite(pendingTimeoutMinutes) && (pendingTimeoutMinutes ?? 0) > 0
    ? Number(pendingTimeoutMinutes)
    : 10;
  return Math.max(60_000, minutes * 60_000);
}

export async function pollAnalysisUntilSettled(
  initial: Analysis,
  options: {
    fetchById: (id: number) => Promise<Analysis>;
    timeoutMs: number;
    now?: () => number;
    sleep?: (ms: number) => Promise<void>;
    onProgress?: (current: Analysis) => void;
  },
): Promise<AnalysisPollResult> {
  let current = initial;
  options.onProgress?.(current);
  const now = options.now ?? Date.now;
  const sleep = options.sleep ?? ((ms: number) => new Promise((resolve) => setTimeout(resolve, ms)));
  const deadline = now() + options.timeoutMs;

  while (current.status === "PENDING" && now() < deadline) {
    await sleep(pollIntervalMs(deadline - now()));
    current = await options.fetchById(current.id);
    options.onProgress?.(current);
  }

  return {
    analysis: current,
    timedOut: current.status === "PENDING",
  };
}

function pollIntervalMs(remainingMs: number) {
  if (remainingMs > 5 * 60_000) return 2_000;
  if (remainingMs > 60_000) return 1_500;
  return 1_000;
}
