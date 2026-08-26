export const EXTENSION_BRIDGE_CHANNEL = "resumelens-browser-extension-v1";

export type ApiRequester = <T>(path: string, init?: RequestInit) => Promise<T>;

export type ExtensionBridgeRequest = {
  channel: typeof EXTENSION_BRIDGE_CHANNEL;
  direction: "request";
  requestId: string;
  action: "prepare" | "latestAnalysis" | "analyze" | "getAnalysis";
  payload?: unknown;
};

type SourceIdentity = {
  sourcePlatform: string;
  sourceJobId: string;
};

type LatestAnalysisPayload = {
  resumeId: number;
  jobDescriptionId: number;
};

type PageData<T> = { content: T[] };
type JobSourceLookup<T> = { found: boolean; job: T | null };

export async function dispatchExtensionBridgeRequest(
  request: ExtensionBridgeRequest,
  requestApi: ApiRequester,
): Promise<unknown> {
  switch (request.action) {
    case "prepare": {
      const source = sourceIdentity(request.payload);
      const query = new URLSearchParams(source).toString();
      const [resumePage, lookup] = await Promise.all([
        requestApi<PageData<unknown>>("/api/resumes?size=50"),
        requestApi<JobSourceLookup<unknown>>(`/api/job-descriptions/source?${query}`),
      ]);
      return { resumes: resumePage.content, existingJob: lookup.found ? lookup.job : null };
    }
    case "latestAnalysis": {
      const payload = latestAnalysisPayload(request.payload);
      const query = new URLSearchParams({
        resumeId: String(payload.resumeId),
        jobDescriptionId: String(payload.jobDescriptionId),
      });
      return requestApi<unknown>(`/api/analysis-histories/latest?${query}`);
    }
    case "analyze":
      return requestApi<unknown>("/api/browser-extension/analyze", {
        method: "POST",
        body: JSON.stringify(objectPayload(request.payload, "分析参数不能为空")),
      });
    case "getAnalysis": {
      const payload = objectPayload(request.payload, "分析记录参数不能为空");
      const id = positiveId(payload.id, "analysis id");
      return requestApi<unknown>(`/api/analysis-histories/${id}`);
    }
  }
}

export function isExtensionBridgeRequest(value: unknown): value is ExtensionBridgeRequest {
  if (!value || typeof value !== "object") return false;
  const item = value as Record<string, unknown>;
  return item.channel === EXTENSION_BRIDGE_CHANNEL
    && item.direction === "request"
    && typeof item.requestId === "string"
    && item.requestId.length > 0
    && item.requestId.length <= 100
    && ["prepare", "latestAnalysis", "analyze", "getAnalysis"].includes(String(item.action));
}

function sourceIdentity(value: unknown): SourceIdentity {
  const payload = objectPayload(value, "岗位来源参数不能为空");
  const sourcePlatform = stringValue(payload.sourcePlatform, "sourcePlatform", 32);
  const sourceJobId = stringValue(payload.sourceJobId, "sourceJobId", 160);
  return { sourcePlatform, sourceJobId };
}

function latestAnalysisPayload(value: unknown): LatestAnalysisPayload {
  const payload = objectPayload(value, "历史分析参数不能为空");
  return {
    resumeId: positiveId(payload.resumeId, "resume id"),
    jobDescriptionId: positiveId(payload.jobDescriptionId, "job description id"),
  };
}

function objectPayload(value: unknown, message: string): Record<string, unknown> {
  if (!value || typeof value !== "object" || Array.isArray(value)) throw new Error(message);
  return value as Record<string, unknown>;
}

function stringValue(value: unknown, name: string, maxLength: number) {
  const text = typeof value === "string" ? value.trim() : "";
  if (!text || text.length > maxLength) throw new Error(`${name} 格式不正确`);
  return text;
}

function positiveId(value: unknown, name: string) {
  const id = Number(value);
  if (!Number.isSafeInteger(id) || id <= 0) throw new Error(`${name} 格式不正确`);
  return id;
}
