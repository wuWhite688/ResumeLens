/** Shared API client and domain types for ResumeLens. */

export type User = { id: number; username: string; displayName: string; email: string };

export type Resume = {
  id: number;
  title: string;
  candidateName: string;
  phone?: string;
  email?: string;
  rawText: string;
  originalFileName?: string;
  contentType?: string;
  fileExtension?: string;
  fileSize?: number;
  createdAt?: string;
  updatedAt?: string;
};

export type Job = {
  id: number;
  title: string;
  companyName: string;
  location?: string;
  employmentType?: string;
  description: string;
  requirements?: string;
  createdAt?: string;
  updatedAt?: string;
};

export type JobDraft = {
  title: string;
  companyName: string;
  location?: string;
  employmentType?: string;
  description: string;
  requirements?: string;
};

export type Analysis = {
  id: number;
  resumeId: number;
  resumeTitle: string;
  jobDescriptionId: number;
  jobTitle: string;
  matchScore: number | null;
  status: "PENDING" | "COMPLETED" | "FAILED";
  summary?: string;
  retrievedContext?: string;
  strengths?: string;
  missingSkills?: string;
  improvementSuggestions?: string;
  interviewQuestions?: string;
  createdAt: string;
};

export type ApiEnvelope<T> = { success: boolean; code: string; message: string; data: T };
export type PageData<T> = { content: T[]; totalElements: number };
export type AuthResponse = {
  tokenType: "Bearer";
  accessToken: string;
  expiresInSeconds: number;
  user: User;
};

export const API_PREFIX = "/api/backend";
export const AUTH_EXPIRED_EVENT = "jd-rag-auth-expired";

type ApiRequestOptions = {
  auth?: boolean;
  retryAuth?: boolean;
};

let accessToken = "";
let refreshPromise: Promise<AuthResponse | null> | null = null;

export function setAccessToken(nextToken: string) {
  accessToken = nextToken;
  removeLegacyAuthStorage();
}

export function clearAuthSession() {
  accessToken = "";
  removeLegacyAuthStorage();
}

export async function refreshSession(): Promise<AuthResponse | null> {
  if (refreshPromise) return refreshPromise;

  refreshPromise = requestRefreshWithBrowserLock()
    .then((session) => {
      setAccessToken(session.accessToken);
      return session;
    })
    .catch(() => {
      clearAuthSession();
      return null;
    })
    .finally(() => {
      refreshPromise = null;
    });
  return refreshPromise;
}

export async function logoutSession(): Promise<void> {
  try {
    await fetch(`${API_PREFIX}/api/auth/logout`, {
      method: "POST",
      credentials: "same-origin",
      headers: { Accept: "application/json" },
    });
  } catch {
    // Local session cleanup must still complete when the backend is unavailable.
  } finally {
    clearAuthSession();
  }
}

export async function apiRequest<T>(
  path: string,
  init: RequestInit = {},
  options: ApiRequestOptions = {},
): Promise<T> {
  const headers = new Headers(init.headers);
  headers.set("Accept", "application/json");
  const requestAccessToken = accessToken;
  if (options.auth !== false && requestAccessToken) {
    headers.set("Authorization", `Bearer ${requestAccessToken}`);
  }
  if (init.body && !(init.body instanceof FormData) && !headers.has("Content-Type")) {
    headers.set("Content-Type", "application/json; charset=utf-8");
  }
  const response = await fetch(`${API_PREFIX}${path}`, {
    ...init,
    headers,
    credentials: "same-origin",
  });
  if (response.status === 401 && options.auth !== false) {
    if (options.retryAuth !== false) {
      if (requestAccessToken && requestAccessToken !== accessToken) {
        return apiRequest<T>(path, init, { ...options, retryAuth: false });
      }
      const session = await refreshSession();
      if (session) {
        return apiRequest<T>(path, init, { ...options, retryAuth: false });
      }
    }
    notifyAuthExpired();
  }
  if (response.status === 204) {
    return undefined as T;
  }
  const payload = (await response.json().catch(() => null)) as ApiEnvelope<T> | null;
  if (!response.ok || !payload?.success) {
    throw new Error(payload?.message || `请求失败（${response.status}）`);
  }
  return payload.data;
}

async function requestSession(path: string): Promise<AuthResponse> {
  const response = await fetch(`${API_PREFIX}${path}`, {
    method: "POST",
    credentials: "same-origin",
    headers: { Accept: "application/json" },
  });
  const payload = (await response.json().catch(() => null)) as ApiEnvelope<AuthResponse> | null;
  if (!response.ok || !payload?.success) {
    throw new Error(payload?.message || `session request failed (${response.status})`);
  }
  return payload.data;
}

async function requestRefreshWithBrowserLock(): Promise<AuthResponse> {
  if (typeof navigator !== "undefined" && navigator.locks) {
    return navigator.locks.request("jd-rag-refresh-token", () => requestSession("/api/auth/refresh"));
  }
  return requestSession("/api/auth/refresh");
}

function notifyAuthExpired() {
  clearAuthSession();
  if (typeof window !== "undefined") {
    window.dispatchEvent(new Event(AUTH_EXPIRED_EVENT));
  }
}

function removeLegacyAuthStorage() {
  if (typeof window === "undefined") return;
  localStorage.removeItem("jd-rag-token");
  localStorage.removeItem("jd-rag-user");
}

/** Parse JSON array or NDJSON / multi-object text into JobDraft list for bulk import. */
export function parseJobImportPayload(raw: string): JobDraft[] {
  const text = raw.trim();
  if (!text) throw new Error("导入内容为空");

  // Single JSON array or { items: [...] }
  try {
    const parsed = JSON.parse(text) as unknown;
    if (Array.isArray(parsed)) return parsed.map(normalizeJobDraft);
    if (parsed && typeof parsed === "object" && Array.isArray((parsed as { items?: unknown }).items)) {
      return ((parsed as { items: unknown[] }).items).map(normalizeJobDraft);
    }
    if (parsed && typeof parsed === "object") {
      return [normalizeJobDraft(parsed)];
    }
  } catch (error) {
    // Keep validation errors from normalizeJobDraft; only fall through on JSON syntax issues.
    if (error instanceof Error && !error.message.startsWith("Unexpected") && !/JSON/i.test(error.message)) {
      throw error;
    }
  }

  // NDJSON: one object per line
  const lines = text.split(/\r?\n/).map((line) => line.trim()).filter(Boolean);
  if (lines.length > 1) {
    const objects: JobDraft[] = [];
    for (const line of lines) {
      try {
        objects.push(normalizeJobDraft(JSON.parse(line)));
      } catch {
        throw new Error(`无法解析行：${line.slice(0, 80)}`);
      }
    }
    return objects;
  }

  throw new Error("请粘贴 JSON 数组，例如 [{\"title\":\"...\",\"companyName\":\"...\",\"description\":\"...\"}]");
}

function normalizeJobDraft(value: unknown): JobDraft {
  if (!value || typeof value !== "object") {
    throw new Error("每条 JD 必须是对象");
  }
  const item = value as Record<string, unknown>;
  const title = String(item.title ?? "").trim();
  const companyName = String(item.companyName ?? "").trim();
  const description = String(item.description ?? "").trim();
  if (!title || !companyName || !description) {
    throw new Error("每条 JD 需要 title、companyName、description");
  }
  return {
    title,
    companyName,
    location: item.location != null ? String(item.location) : "",
    employmentType: item.employmentType != null ? String(item.employmentType) : "全职",
    description,
    requirements: item.requirements != null ? String(item.requirements) : "",
  };
}

export const SAMPLE_BULK_JOBS: JobDraft[] = [
  {
    title: "Java 后端工程师",
    companyName: "示例科技",
    location: "杭州",
    employmentType: "全职",
    description: "负责招聘业务后端服务开发与维护，参与接口设计与性能优化。",
    requirements: "Java、Spring Boot、MySQL；了解 JWT 与 REST。",
  },
  {
    title: "RAG 应用工程师",
    companyName: "示例智能",
    location: "远程",
    employmentType: "全职",
    description: "建设企业内部知识库 RAG 链路，完成分块、检索与生成式问答。",
    requirements: "Embedding、向量检索、Python 或 Java；加分 LLM 对接经验。",
  },
];
