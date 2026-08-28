"use client";

import Link from "next/link";
import { useCallback, useEffect, useMemo, useRef, useState } from "react";
import type { ChangeEvent, CSSProperties, FormEvent } from "react";
import { BackendStatus } from "./components/BackendStatus";
import {
  AUTH_EXPIRED_EVENT,
  SAMPLE_BULK_JOBS,
  type Analysis,
  type AnalysisSummary,
  type AiStatus,
  type AuthResponse,
  type Job,
  type JobSemanticMatch,
  type PageData,
  type Resume,
  type User,
  apiRequest,
  logoutSession,
  parseJobImportPayload,
  refreshSession,
  setAccessToken,
} from "./lib/api";
import {
  PREVIEW_STRENGTHS,
  annotatePreviewEvidence,
  previewConclusion,
} from "./preview-evidence";
import {
  asList,
  buildReportMarkdown,
  buildReportPrintHtml,
  downloadTextFile,
  evidenceChunks,
  openPrintableReport,
  parseRagMeta,
  reportFilename,
} from "./report-export";
import {
  analysisPollTimeoutMs,
  mergeLatestAnalysisSummaries,
  pollAnalysisUntilSettled,
} from "./analysis-poll";
import { appendResumeUploadFields, prepareResumeUploadDraft, resumeFormFrom } from "./resume-upload";
import { semanticAnalysisTargets } from "./semantic-ranking";

const EMPTY_RESUME_FORM = {
  title: "",
  candidateName: "",
  phone: "",
  email: "",
  rawText: "",
};
const EMPTY_JOB_FORM = {
  title: "",
  companyName: "",
  location: "",
  employmentType: "全职",
  description: "",
  requirements: "",
};
const HISTORY_PREVIEW_LIMIT = 8;
const HISTORY_PAGE_SIZE = 20;
const JOB_PAGE_SIZE = 50;
type JobSort = "recent" | "semantic" | "score" | "analyzed";
type JobFilter = "all" | "unanalyzed";
type JobAnalysesStatus = "idle" | "loading" | "ready" | "error";
type JobSemanticStatus = "idle" | "loading" | "ready" | "error";
type AnalysisRun = { generation: number; resumeId: number; jobId: number };

function analysisTimestamp(item?: AnalysisSummary) {
  const timestamp = item?.createdAt ? Date.parse(item.createdAt) : Number.NaN;
  return Number.isFinite(timestamp) ? timestamp : -1;
}

function analysisScore(item?: AnalysisSummary) {
  const score = Number(item?.matchScore);
  return item?.status === "COMPLETED" && Number.isFinite(score) ? score : -1;
}

function jobScoreLabel(item?: AnalysisSummary) {
  if (!item) return "未分析";
  if (item.status === "PENDING") return "分析中";
  if (item.status === "FAILED") return "失败";
  const score = Number(item.matchScore);
  return Number.isFinite(score) ? `${score.toFixed(0)} 分` : "—";
}

function jobTimestamp(item: Job) {
  const timestamp = item.createdAt ? Date.parse(item.createdAt) : Number.NaN;
  return Number.isFinite(timestamp) ? timestamp : -1;
}

function renderCitedText(text: string) {
  const parts = text.split(/(\[chunk-\d+\])/g);
  return parts.map((part, index) => {
    const cite = part.match(/^\[chunk-(\d+)\]$/);
    if (!cite) return <span key={index}>{part}</span>;
    return (
      <a key={index} className="cite" href={`#chunk-${cite[1]}`} onClick={(event) => {
        event.preventDefault();
        const target = document.getElementById(`chunk-${cite[1]}`);
        if (target instanceof HTMLDetailsElement) {
          target.open = true;
          target.classList.add("highlight");
          target.scrollIntoView({ behavior: "smooth", block: "center" });
          window.setTimeout(() => target.classList.remove("highlight"), 1400);
        }
      }}>
        {part}
      </a>
    );
  });
}

function scoreTone(score = 0) {
  if (score >= 85) return "excellent";
  if (score >= 70) return "good";
  return "watch";
}

const SAMPLE_RESUME = {
  title: "Java 后端开发简历",
  candidateName: "张三",
  phone: "13800000000",
  email: "zhangsan@example.com",
  rawText: `技能
熟练掌握 Java、Spring Boot、MySQL、JWT 与 REST API 开发；有权限体系与全局异常处理实践。

工作经历
2023-2025 某互联网公司后端开发：维护招聘业务服务，设计简历/职位表结构，完成用户数据隔离；参与登录鉴权改造，引入 JWT。

项目经历
负责简历匹配系统中的 RAG 模块：文本分块、本地 Embedding、Top-K 召回与证据拼装，支持中英文；对接 DeepSeek Chat Completions，约束 JSON 输出。
使用 Apache Tika 解析 PDF/DOCX 简历文本，并提供 rawText 校对入口。

教育
本科 · 计算机科学与技术 · 主修数据结构、操作系统、计算机网络。`,
};

const SAMPLE_JOB = {
  title: "RAG 平台工程师",
  companyName: "某科技",
  location: "杭州",
  employmentType: "全职",
  description: "负责企业知识库与招聘场景的 RAG 链路建设，覆盖解析、分块、向量检索与生成式分析，和业务一起把匹配结果做得可解释。",
  requirements: "Java / Spring Boot / MySQL；熟悉 Embedding、向量检索；有 JWT/权限与 API 设计经验；加分：DeepSeek/OpenAI 对接、简历解析。",
};

function configuredModel(status: AiStatus | null) {
  return status?.model.trim() || "模型未配置";
}

function runtimeModeLabel(status: AiStatus | null) {
  if (!status) return "AI 运行模式确认中";
  return status.mockEnabled
    ? "演示模式（离线 mock），未调用真实模型"
    : `当前生成模型：${configuredModel(status)}`;
}

function generationProgressLabel(status: AiStatus | null) {
  if (!status) return "生成模式确认中，正在准备分析…";
  return status.mockEnabled
    ? "演示模式（离线 mock），未调用真实模型"
    : `${configuredModel(status)} 正在生成分析…`;
}

function pendingAnalysisLabel(status: AiStatus | null) {
  if (!status) return "本地检索与生成分析正在进行（运行模式确认中），完成后会自动更新。";
  return status.mockEnabled
    ? "本地检索与离线 mock 演示分析正在进行；演示模式（离线 mock），未调用真实模型。完成后会自动更新。"
    : `本地检索与 ${configuredModel(status)} 分析正在进行，完成后会自动更新。`;
}

/**
 * 检索阈值与 Top-K 一律从 /api/ai/status 读取当前服务端配置。
 * 这些数字曾经写死在界面里，服务端阈值调整后界面仍显示旧值，
 * 展示出来的检索策略与实际行为对不上。
 */
function minSimilarityText(status: AiStatus | null) {
  return typeof status?.minSimilarity === "number" ? status.minSimilarity.toFixed(2) : "—";
}

function topKText(status: AiStatus | null) {
  return typeof status?.topK === "number" ? String(status.topK) : "—";
}

export default function Home() {
  const [token, setToken] = useState("");
  const [sidebarCollapsed, setSidebarCollapsed] = useState(
    () => typeof window !== "undefined" && localStorage.getItem("jd-rag-sidebar-collapsed") === "true",
  );
  const [user, setUser] = useState<User | null>(null);
  const [authMode, setAuthMode] = useState<"login" | "register">("login");
  const [auth, setAuth] = useState({ username: "", password: "", email: "", displayName: "" });
  const [resumes, setResumes] = useState<Resume[]>([]);
  const [jobs, setJobs] = useState<Job[]>([]);
  const [jobsTotal, setJobsTotal] = useState(0);
  const [jobsPage, setJobsPage] = useState(0);
  const [jobsLoadingMore, setJobsLoadingMore] = useState(false);
  const [jobSort, setJobSort] = useState<JobSort>("recent");
  const [jobFilter, setJobFilter] = useState<JobFilter>("all");
  const [jobLatestAnalyses, setJobLatestAnalyses] = useState<AnalysisSummary[]>([]);
  const [jobAnalysesResumeId, setJobAnalysesResumeId] = useState<number | null>(null);
  const [jobAnalysesStatus, setJobAnalysesStatus] = useState<JobAnalysesStatus>("idle");
  const [jobAnalysesError, setJobAnalysesError] = useState("");
  const [jobSemanticMatches, setJobSemanticMatches] = useState<JobSemanticMatch[]>([]);
  const [jobSemanticResumeId, setJobSemanticResumeId] = useState<number | null>(null);
  const [jobSemanticStatus, setJobSemanticStatus] = useState<JobSemanticStatus>("idle");
  const [jobSemanticError, setJobSemanticError] = useState("");
  const [topMatchCount, setTopMatchCount] = useState(5);
  const [history, setHistory] = useState<Analysis[]>([]);
  const [historyTotal, setHistoryTotal] = useState(0);
  const [historyPage, setHistoryPage] = useState(0);
  const [historyExpanded, setHistoryExpanded] = useState(false);
  const [historyLoadingMore, setHistoryLoadingMore] = useState(false);
  const [selectedResumeId, setSelectedResumeId] = useState<number | "">("");
  const [selectedJobId, setSelectedJobId] = useState<number | "">("");
  const [analysis, setAnalysis] = useState<Analysis | null>(null);
  const [busy, setBusy] = useState("");
  const [error, setError] = useState("");
  const [notice, setNotice] = useState("");
  const [file, setFile] = useState<File | null>(null);
  const [resumeForm, setResumeForm] = useState({ ...SAMPLE_RESUME });
  const [jobForm, setJobForm] = useState({ ...SAMPLE_JOB });
  const [editingResumeId, setEditingResumeId] = useState<number | null>(null);
  const [editingJobId, setEditingJobId] = useState<number | null>(null);
  const [bulkImportText, setBulkImportText] = useState(() => JSON.stringify(SAMPLE_BULK_JOBS, null, 2));
  const [showBulkImport, setShowBulkImport] = useState(false);
  const [evidenceFilter, setEvidenceFilter] = useState<"kept" | "all" | "boost">("kept");
  const [aiStatus, setAiStatus] = useState<AiStatus | null>(null);
  const analysisRunSequence = useRef(0);
  const activeAnalysisRun = useRef<AnalysisRun | null>(null);
  const jobAnalysesRequestGeneration = useRef(0);
  const jobSemanticRequestGeneration = useRef(0);

  const chooseResume = useCallback((next: number | "") => {
    const run = activeAnalysisRun.current;
    if (run && run.resumeId !== next) activeAnalysisRun.current = null;
    jobAnalysesRequestGeneration.current += 1;
    jobSemanticRequestGeneration.current += 1;
    setJobLatestAnalyses([]);
    setJobAnalysesResumeId(null);
    setJobAnalysesStatus(next ? "loading" : "idle");
    setJobAnalysesError("");
    setJobSemanticMatches([]);
    setJobSemanticResumeId(null);
    setJobSemanticStatus("idle");
    setJobSemanticError("");
    setAnalysis((current) => current && current.resumeId !== next ? null : current);
    setSelectedResumeId(next);
  }, []);

  const chooseJob = useCallback((next: number | "") => {
    const run = activeAnalysisRun.current;
    if (run && run.jobId !== next) activeAnalysisRun.current = null;
    setAnalysis((current) => current && current.jobDescriptionId !== next ? null : current);
    setSelectedJobId(next);
  }, []);

  function clearAuthenticatedView() {
    activeAnalysisRun.current = null;
    jobAnalysesRequestGeneration.current += 1;
    jobSemanticRequestGeneration.current += 1;
    setToken("");
    setUser(null);
    setAnalysis(null);
    setResumes([]);
    setJobs([]);
    setJobsTotal(0);
    setJobsPage(0);
    setJobLatestAnalyses([]);
    setJobAnalysesResumeId(null);
    setJobAnalysesStatus("idle");
    setJobAnalysesError("");
    setJobSemanticMatches([]);
    setJobSemanticResumeId(null);
    setJobSemanticStatus("idle");
    setJobSemanticError("");
    setHistory([]);
    setHistoryTotal(0);
    setHistoryPage(0);
    setHistoryExpanded(false);
    setSelectedResumeId("");
    setSelectedJobId("");
    setEditingResumeId(null);
    setEditingJobId(null);
    setFile(null);
    setResumeForm({ ...SAMPLE_RESUME });
    setJobForm({ ...SAMPLE_JOB });
    setAuth({ username: "", password: "", email: "", displayName: "" });
    setBulkImportText(JSON.stringify(SAMPLE_BULK_JOBS, null, 2));
    setShowBulkImport(false);
    setNotice("");
  }

  function invalidateSemanticMatches() {
    jobSemanticRequestGeneration.current += 1;
    setJobSemanticMatches([]);
    setJobSemanticResumeId(null);
    setJobSemanticStatus("idle");
    setJobSemanticError("");
  }

  function beginAnalysisRun(resumeId: number, jobId: number) {
    const run = { generation: ++analysisRunSequence.current, resumeId, jobId };
    activeAnalysisRun.current = run;
    return run;
  }

  function isCurrentAnalysisRun(run: AnalysisRun) {
    return activeAnalysisRun.current?.generation === run.generation;
  }

  function acceptAnalysisProgress(next: Analysis, run: AnalysisRun) {
    if (!isCurrentAnalysisRun(run)) return;
    setAnalysis(next);
    setHistory((items) => [next, ...items.filter((item) => item.id !== next.id)]);
    setJobLatestAnalyses((items) => mergeLatestAnalysisSummaries(items, [next]));
  }

  async function waitForAnalysis(initial: Analysis, run: AnalysisRun) {
    const { analysis: current, timedOut, cancelled } = await pollAnalysisUntilSettled(initial, {
      timeoutMs: analysisPollTimeoutMs(aiStatus?.pendingTimeoutMinutes),
      fetchById: (id) => apiRequest<Analysis>(`/api/analysis-histories/${id}`),
      shouldContinue: () => isCurrentAnalysisRun(run),
      onProgress: (next) => acceptAnalysisProgress(next, run),
    });

    if (cancelled || !isCurrentAnalysisRun(run)) return null;
    activeAnalysisRun.current = null;
    if (timedOut) {
      setNotice("分析仍在后台运行，可稍后在历史记录中查看结果");
      return current;
    }
    if (current.status === "FAILED") throw new Error(current.summary || "AI 分析失败");
    return current;
  }

  const loadWorkspace = useCallback(async () => {
    setBusy("loading");
    setError("");
    try {
      const [resumePage, jobPage, historyPage] = await Promise.all([
        apiRequest<PageData<Resume>>("/api/resumes?size=50"),
        apiRequest<PageData<Job>>(`/api/job-descriptions?page=0&size=${JOB_PAGE_SIZE}`),
        apiRequest<PageData<Analysis>>(`/api/analysis-histories?page=0&size=${HISTORY_PAGE_SIZE}`),
      ]);
      setResumes(resumePage.content);
      setJobs(jobPage.content);
      setJobsTotal(jobPage.totalElements);
      setJobsPage(0);
      jobSemanticRequestGeneration.current += 1;
      setJobSemanticMatches([]);
      setJobSemanticResumeId(null);
      setJobSemanticStatus("idle");
      setJobSemanticError("");
      setHistory(historyPage.content);
      setHistoryTotal(historyPage.totalElements);
      setHistoryPage(0);
      if (!selectedResumeId && resumePage.content[0]) chooseResume(resumePage.content[0].id);
      if (!selectedJobId && jobPage.content[0]) chooseJob(jobPage.content[0].id);
      if (!analysis && historyPage.content[0]?.status === "COMPLETED") setAnalysis(historyPage.content[0]);
    } catch (reason) {
      setError(reason instanceof Error ? reason.message : "无法连接后端");
    } finally {
      setBusy("");
    }
  }, [analysis, chooseJob, chooseResume, selectedJobId, selectedResumeId]);

  const loadJobAnalyses = useCallback(async (resumeId: number) => {
    const generation = ++jobAnalysesRequestGeneration.current;
    await Promise.resolve();
    if (generation !== jobAnalysesRequestGeneration.current) return;
    setJobLatestAnalyses([]);
    setJobAnalysesResumeId(null);
    setJobAnalysesStatus("loading");
    setJobAnalysesError("");
    try {
      const items = await apiRequest<AnalysisSummary[]>(
        `/api/analysis-histories/latest-by-resume?resumeId=${resumeId}`,
      );
      if (generation !== jobAnalysesRequestGeneration.current) return;
      setJobLatestAnalyses((current) => mergeLatestAnalysisSummaries(items, current));
      setJobAnalysesResumeId(resumeId);
      setJobAnalysesStatus("ready");
    } catch (reason) {
      if (generation !== jobAnalysesRequestGeneration.current) return;
      const message = reason instanceof Error ? reason.message : "无法读取职位匹配分";
      setJobAnalysesResumeId(resumeId);
      setJobAnalysesStatus("error");
      setJobAnalysesError(message);
      setError(message);
    }
  }, []);

  const loadSemanticMatches = useCallback(async (resumeId: number) => {
    const generation = ++jobSemanticRequestGeneration.current;
    setJobSemanticMatches([]);
    setJobSemanticResumeId(null);
    setJobSemanticStatus("loading");
    setJobSemanticError("");
    try {
      const items = await apiRequest<JobSemanticMatch[]>(
        `/api/job-descriptions/matches?resumeId=${resumeId}&limit=200`,
      );
      if (generation !== jobSemanticRequestGeneration.current) return null;
      setJobSemanticMatches(items);
      setJobSemanticResumeId(resumeId);
      setJobSemanticStatus("ready");
      // The match endpoint always covers the complete per-user library (maximum 200).
      setJobs(items.map((item) => item.job));
      setJobsTotal(items.length);
      setJobsPage(Math.max(0, Math.ceil(items.length / JOB_PAGE_SIZE) - 1));
      return items;
    } catch (reason) {
      if (generation !== jobSemanticRequestGeneration.current) return null;
      const message = reason instanceof Error ? reason.message : "无法完成职位向量粗排";
      setJobSemanticResumeId(resumeId);
      setJobSemanticStatus("error");
      setJobSemanticError(message);
      setError(message);
      return null;
    }
  }, []);

  useEffect(() => {
    if (!token || !selectedResumeId) return;
    const timer = window.setTimeout(() => void loadJobAnalyses(selectedResumeId), 0);
    return () => window.clearTimeout(timer);
  }, [loadJobAnalyses, selectedResumeId, token]);

  useEffect(() => {
    if (!token || !selectedResumeId || jobSort !== "semantic") return;
    if (jobSemanticResumeId === selectedResumeId
        && (jobSemanticStatus === "loading" || jobSemanticStatus === "ready")) return;
    const timer = window.setTimeout(() => void loadSemanticMatches(Number(selectedResumeId)), 0);
    return () => window.clearTimeout(timer);
  }, [jobSemanticResumeId, jobSemanticStatus, jobSort, loadSemanticMatches, selectedResumeId, token]);

  async function loadMoreJobs() {
    if (jobsLoadingMore || jobs.length >= jobsTotal) return;
    setJobsLoadingMore(true);
    setError("");
    try {
      const nextPage = jobsPage + 1;
      const page = await apiRequest<PageData<Job>>(
        `/api/job-descriptions?page=${nextPage}&size=${JOB_PAGE_SIZE}`,
      );
      setJobs((items) => {
        const seen = new Set(items.map((item) => item.id));
        return [...items, ...page.content.filter((item) => !seen.has(item.id))];
      });
      setJobsTotal(page.totalElements);
      setJobsPage(nextPage);
    } catch (reason) {
      setError(reason instanceof Error ? reason.message : "无法加载更多职位");
    } finally {
      setJobsLoadingMore(false);
    }
  }

  async function loadAllJobsForRanking() {
    if (jobsLoadingMore || jobs.length >= jobsTotal) return;
    setJobsLoadingMore(true);
    setError("");
    try {
      const pageCount = Math.ceil(jobsTotal / JOB_PAGE_SIZE);
      const pages = await Promise.all(
        Array.from({ length: pageCount }, (_, page) =>
          apiRequest<PageData<Job>>(`/api/job-descriptions?page=${page}&size=${JOB_PAGE_SIZE}`),
        ),
      );
      const seen = new Set<number>();
      setJobs(pages.flatMap((page) => page.content).filter((item) => {
        if (seen.has(item.id)) return false;
        seen.add(item.id);
        return true;
      }));
      setJobsTotal(pages[0]?.totalElements ?? jobsTotal);
      setJobsPage(Math.max(0, pageCount - 1));
    } catch (reason) {
      setError(reason instanceof Error ? reason.message : "无法加载完整职位库");
    } finally {
      setJobsLoadingMore(false);
    }
  }

  async function loadMoreHistory() {
    if (historyLoadingMore || history.length >= historyTotal) return;
    setHistoryLoadingMore(true);
    setError("");
    try {
      const nextPage = historyPage + 1;
      const page = await apiRequest<PageData<Analysis>>(
        `/api/analysis-histories?page=${nextPage}&size=${HISTORY_PAGE_SIZE}`,
      );
      setHistory((items) => {
        const seen = new Set(items.map((item) => item.id));
        return [...items, ...page.content.filter((item) => !seen.has(item.id))];
      });
      setHistoryTotal(page.totalElements);
      setHistoryPage(nextPage);
    } catch (reason) {
      setError(reason instanceof Error ? reason.message : "无法加载更多分析记录");
    } finally {
      setHistoryLoadingMore(false);
    }
  }

  useEffect(() => {
    let active = true;
    const params = new URLSearchParams(window.location.search);
    const resumeIdParam = Number(params.get("resumeId"));
    const jobIdParam = Number(params.get("jobId"));
    const analysisIdParam = Number(params.get("analysisId"));
    const locationTimer = window.setTimeout(() => {
      if (Number.isFinite(resumeIdParam) && resumeIdParam > 0) chooseResume(resumeIdParam);
      if (Number.isFinite(jobIdParam) && jobIdParam > 0) chooseJob(jobIdParam);
    }, 0);

    const handleAuthExpired = () => {
      clearAuthenticatedView();
      setError("登录已过期，请重新登录");
    };
    window.addEventListener(AUTH_EXPIRED_EVENT, handleAuthExpired);
    void apiRequest<AiStatus>("/api/ai/status", {}, { auth: false })
      .then((status) => {
        if (active) setAiStatus(status);
      })
      .catch(() => {
        // Keep the UI neutral when runtime mode cannot be confirmed.
      });
    void refreshSession().then(async (session) => {
      if (!session || !active) return;
      setToken(session.accessToken);
      setUser(session.user);
      await loadWorkspace();
      if (Number.isFinite(analysisIdParam) && analysisIdParam > 0 && active) {
        try {
          const requestedAnalysis = await apiRequest<Analysis>(`/api/analysis-histories/${analysisIdParam}`);
          if (active) setAnalysis(requestedAnalysis);
        } catch (reason) {
          if (active) setError(reason instanceof Error ? reason.message : "无法读取指定分析报告");
        }
      }
    });
    return () => {
      active = false;
      window.clearTimeout(locationTimer);
      window.removeEventListener(AUTH_EXPIRED_EVENT, handleAuthExpired);
    };
  // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  async function submitAuth(event: FormEvent) {
    event.preventDefault();
    setBusy("auth");
    setError("");
    try {
      const path = authMode === "login" ? "/api/auth/login" : "/api/auth/register";
      const body = authMode === "login"
        ? { username: auth.username, password: auth.password }
        : auth;
      const result = await apiRequest<AuthResponse>(
        path,
        { method: "POST", body: JSON.stringify(body) },
        { auth: false },
      );
      setAccessToken(result.accessToken);
      setToken(result.accessToken);
      setUser(result.user);
      setNotice(`欢迎回来，${result.user.displayName}`);
      await loadWorkspace();
    } catch (reason) {
      setError(reason instanceof Error ? reason.message : "登录失败");
    } finally {
      setBusy("");
    }
  }

  function logout() {
    void logoutSession();
    clearAuthenticatedView();
  }

  function toggleSidebar() {
    setSidebarCollapsed((current) => {
      localStorage.setItem("jd-rag-sidebar-collapsed", String(!current));
      return !current;
    });
  }

  async function chooseFile(event: ChangeEvent<HTMLInputElement>) {
    const nextFile = event.target.files?.[0] || null;
    setFile(nextFile);
    if (!nextFile) return;
    setResumeForm((current) => prepareResumeUploadDraft(current, nextFile.name));
  }

  async function beginEditResume(item: Resume) {
    setBusy("resume-edit");
    setError("");
    try {
      const detail = await apiRequest<Resume>(`/api/resumes/${item.id}`);
      setEditingResumeId(detail.id);
      setFile(null);
      setResumeForm(resumeFormFrom(detail));
      chooseResume(detail.id);
      setNotice(`正在编辑简历 #${detail.id}，保存后将更新并失效旧向量索引`);
    } catch (reason) {
      setError(reason instanceof Error ? reason.message : "加载简历失败");
    } finally {
      setBusy("");
    }
  }

  function beginEditJob(item: Job) {
    setEditingJobId(item.id);
    setJobForm({
      title: item.title || "",
      companyName: item.companyName || "",
      location: item.location || "",
      employmentType: item.employmentType || "全职",
      description: item.description || "",
      requirements: item.requirements || "",
    });
    chooseJob(item.id);
    setNotice(`正在编辑职位 #${item.id}`);
  }

  function resetResumeEditor() {
    setEditingResumeId(null);
    setFile(null);
    setResumeForm({ ...EMPTY_RESUME_FORM });
  }

  function resetJobEditor() {
    setEditingJobId(null);
    setJobForm({ ...EMPTY_JOB_FORM });
  }

  async function saveResume(event: FormEvent) {
    event.preventDefault();
    setBusy("resume");
    setError("");
    try {
      let saved: Resume;
      if (editingResumeId && file) {
        throw new Error("编辑模式下不支持重新上传文件；请先取消编辑，或清空文件后仅更新文本");
      }
      if (editingResumeId) {
        saved = await apiRequest<Resume>(`/api/resumes/${editingResumeId}`, {
          method: "PUT",
          body: JSON.stringify(resumeForm),
        });
        setResumes((items) => items.map((item) => (item.id === saved.id ? saved : item)));
        setNotice(`简历 #${saved.id} 已更新，下次匹配会重建向量`);
      } else if (file) {
        const form = new FormData();
        form.append("file", file);
        appendResumeUploadFields(form, resumeForm);
        saved = await apiRequest<Resume>("/api/resumes/upload", { method: "POST", body: form });
        setResumes((items) => [saved, ...items.filter((item) => item.id !== saved.id)]);
        setNotice("简历已上传保存，等待向量检索");
      } else {
        saved = await apiRequest<Resume>("/api/resumes", { method: "POST", body: JSON.stringify(resumeForm) });
        setResumes((items) => [saved, ...items.filter((item) => item.id !== saved.id)]);
        setNotice("简历已保存，等待向量检索");
      }
      chooseResume(saved.id);
      invalidateSemanticMatches();
      setEditingResumeId(null);
      setFile(null);
    } catch (reason) {
      setError(reason instanceof Error ? reason.message : "简历保存失败");
    } finally {
      setBusy("");
    }
  }

  async function saveJob(event: FormEvent) {
    event.preventDefault();
    setBusy("job");
    setError("");
    try {
      let saved: Job;
      if (editingJobId) {
        saved = await apiRequest<Job>(`/api/job-descriptions/${editingJobId}`, {
          method: "PUT",
          body: JSON.stringify(jobForm),
        });
        setJobs((items) => items.map((item) => (item.id === saved.id ? saved : item)));
        setNotice(`职位 #${saved.id} 已更新`);
      } else {
        saved = await apiRequest<Job>("/api/job-descriptions", { method: "POST", body: JSON.stringify(jobForm) });
        setJobs((items) => [saved, ...items.filter((item) => item.id !== saved.id)]);
        setJobsTotal((total) => total + 1);
        setNotice("职位 JD 已保存，可以开始匹配");
      }
      chooseJob(saved.id);
      invalidateSemanticMatches();
      setEditingJobId(null);
    } catch (reason) {
      setError(reason instanceof Error ? reason.message : "JD 保存失败");
    } finally {
      setBusy("");
    }
  }

  async function deleteResume(id: number) {
    if (!window.confirm(`确认删除简历 #${id}？将同时清理上传文件与向量索引。`)) return;
    setBusy("resume-delete");
    setError("");
    try {
      await apiRequest<void>(`/api/resumes/${id}`, { method: "DELETE" });
      setResumes((items) => items.filter((item) => item.id !== id));
      if (selectedResumeId === id) chooseResume("");
      if (editingResumeId === id) resetResumeEditor();
      if (analysis?.resumeId === id) setAnalysis(null);
      setNotice(`简历 #${id} 已删除`);
    } catch (reason) {
      setError(reason instanceof Error ? reason.message : "删除简历失败");
    } finally {
      setBusy("");
    }
  }

  async function deleteJob(id: number) {
    if (!window.confirm(`确认删除职位 #${id}？`)) return;
    setBusy("job-delete");
    setError("");
    try {
      await apiRequest<void>(`/api/job-descriptions/${id}`, { method: "DELETE" });
      setJobs((items) => items.filter((item) => item.id !== id));
      setJobsTotal((total) => Math.max(0, total - 1));
      if (selectedJobId === id) chooseJob("");
      if (editingJobId === id) resetJobEditor();
      if (analysis?.jobDescriptionId === id) setAnalysis(null);
      invalidateSemanticMatches();
      setNotice(`职位 #${id} 已删除`);
    } catch (reason) {
      setError(reason instanceof Error ? reason.message : "删除职位失败");
    } finally {
      setBusy("");
    }
  }

  async function bulkImportJobs() {
    setBusy("bulk-import");
    setError("");
    try {
      const items = parseJobImportPayload(bulkImportText);
      const imported = await apiRequest<Job[]>("/api/job-descriptions/import", {
        method: "POST",
        body: JSON.stringify({ items }),
      });
      setJobs((current) => {
        const merged = [...imported, ...current];
        const seen = new Set<number>();
        return merged.filter((item) => {
          if (seen.has(item.id)) return false;
          seen.add(item.id);
          return true;
        });
      });
      setJobsTotal((total) => total + imported.length);
      if (imported[0]) chooseJob(imported[0].id);
      invalidateSemanticMatches();
      setShowBulkImport(false);
      setNotice(`已批量导入 ${imported.length} 条职位（POST /api/job-descriptions/import）`);
    } catch (reason) {
      setError(reason instanceof Error ? reason.message : "批量导入失败");
    } finally {
      setBusy("");
    }
  }

  function fillSampleForms() {
    setFile(null);
    setEditingResumeId(null);
    setEditingJobId(null);
    setResumeForm({ ...SAMPLE_RESUME });
    setJobForm({ ...SAMPLE_JOB });
    setNotice("已填入示例简历与 JD，保存后即可在第 03 步选择并匹配");
  }

  function exportMarkdown() {
    if (!analysis || analysis.status !== "COMPLETED") {
      setError("请先完成一次匹配分析，再导出报告");
      return;
    }
    try {
      const markdown = buildReportMarkdown(analysis);
      downloadTextFile(reportFilename(analysis, "md"), markdown, "text/markdown;charset=utf-8");
      setNotice("已下载 Markdown 报告");
    } catch (reason) {
      setError(reason instanceof Error ? reason.message : "导出 Markdown 失败");
    }
  }

  function exportPdf() {
    if (!analysis || analysis.status !== "COMPLETED") {
      setError("请先完成一次匹配分析，再导出报告");
      return;
    }
    try {
      openPrintableReport(buildReportPrintHtml(analysis));
      setNotice("已打开打印预览：请选择「另存为 PDF」");
    } catch (reason) {
      setError(reason instanceof Error ? reason.message : "导出 PDF 失败");
    }
  }

  async function saveSampleAndAnalyze() {
    setBusy("sample");
    setError("");
    let run: AnalysisRun | null = null;
    try {
      setEditingResumeId(null);
      setEditingJobId(null);
      setFile(null);
      setResumeForm({ ...SAMPLE_RESUME });
      setJobForm({ ...SAMPLE_JOB });
      const createdResume = await apiRequest<Resume>("/api/resumes", {
        method: "POST",
        body: JSON.stringify(SAMPLE_RESUME),
      });
      const createdJob = await apiRequest<Job>("/api/job-descriptions", {
        method: "POST",
        body: JSON.stringify(SAMPLE_JOB),
      });
      setResumes((items) => [createdResume, ...items.filter((item) => item.id !== createdResume.id)]);
      setJobs((items) => [createdJob, ...items.filter((item) => item.id !== createdJob.id)]);
      setJobsTotal((total) => total + 1);
      chooseResume(createdResume.id);
      chooseJob(createdJob.id);
      setNotice("示例数据已保存，正在启动 Hybrid RAG 分析…");
      setBusy("analysis");
      run = beginAnalysisRun(createdResume.id, createdJob.id);
      const result = await apiRequest<Analysis>("/api/analysis-histories/ai", {
        method: "POST",
        body: JSON.stringify({ resumeId: createdResume.id, jobDescriptionId: createdJob.id }),
      });
      const completed = await waitForAnalysis(result, run);
      if (!completed) return;
      setNotice("示例匹配完成，下方报告已更新");
      requestAnimationFrame(() => document.getElementById("result")?.scrollIntoView({ behavior: "smooth" }));
    } catch (reason) {
      if (!run || isCurrentAnalysisRun(run)) {
        activeAnalysisRun.current = null;
        setError(reason instanceof Error ? reason.message : "示例流程失败");
      }
    } finally {
      setBusy("");
    }
  }

  async function runAnalysis() {
    if (!selectedResumeId || !selectedJobId) {
      setError("请先选择简历和职位 JD，或点「一键示例匹配」");
      return;
    }
    setBusy("analysis");
    setError("");
    setNotice(`Hybrid RAG 正在检索（阈值过滤 + 关键词 boost），${generationProgressLabel(aiStatus)}`);
    const resumeId = Number(selectedResumeId);
    const jobId = Number(selectedJobId);
    const run = beginAnalysisRun(resumeId, jobId);
    try {
      const result = await apiRequest<Analysis>("/api/analysis-histories/ai", {
        method: "POST",
        body: JSON.stringify({ resumeId, jobDescriptionId: jobId }),
      });
      const completed = await waitForAnalysis(result, run);
      if (!completed) return;
      setNotice("分析完成，结果已写入 MySQL");
      requestAnimationFrame(() => document.getElementById("result")?.scrollIntoView({ behavior: "smooth" }));
    } catch (reason) {
      if (isCurrentAnalysisRun(run)) {
        activeAnalysisRun.current = null;
        setError(reason instanceof Error ? reason.message : "AI 分析失败");
      }
    } finally {
      setBusy("");
    }
  }

  async function runTopMatchesAnalysis() {
    if (!selectedResumeId) {
      setError("请先选择一份简历，再分析向量粗排 Top N");
      return;
    }
    const resumeId = Number(selectedResumeId);
    setBusy("top-analysis");
    setError("");
    let currentRun: AnalysisRun | null = null;
    try {
      setJobSort("semantic");
      const matches = jobSemanticDataReady
        ? jobSemanticMatches
        : await loadSemanticMatches(resumeId);
      if (!matches) return;
      if (matches.length === 0) {
        setNotice("岗位库还是空的，先保存 JD 才能做向量粗排");
        return;
      }

      const latestItems = jobAnalysisDataReady
        ? jobLatestAnalyses
        : await apiRequest<AnalysisSummary[]>(`/api/analysis-histories/latest-by-resume?resumeId=${resumeId}`);
      setJobLatestAnalyses((current) => mergeLatestAnalysisSummaries(latestItems, current));
      setJobAnalysesResumeId(resumeId);
      setJobAnalysesStatus("ready");
      const candidates = matches.slice(0, topMatchCount);
      const targets = semanticAnalysisTargets(matches, latestItems, topMatchCount);
      const skipped = candidates.length - targets.length;
      if (targets.length === 0) {
        setNotice(`向量 Top ${candidates.length} 都已有当前版本的完整分析，不重复调用 LLM`);
        return;
      }

      let completedCount = 0;
      for (const [index, match] of targets.entries()) {
        chooseJob(match.job.id);
        currentRun = beginAnalysisRun(resumeId, match.job.id);
        setNotice(
          `Top N 精析 ${index + 1}/${targets.length}：${match.job.title}（粗排相似度 ${match.similarity.toFixed(3)}）`,
        );
        const submitted = await apiRequest<Analysis>("/api/analysis-histories/ai", {
          method: "POST",
          body: JSON.stringify({ resumeId, jobDescriptionId: match.job.id }),
        });
        const completed = await waitForAnalysis(submitted, currentRun);
        if (!completed) return;
        if (completed.status === "PENDING") {
          setNotice(`已完成 ${completedCount} 个；当前分析仍在后台运行，Top N 队列先暂停`);
          return;
        }
        completedCount += 1;
      }

      await loadJobAnalyses(resumeId);
      setNotice(`Top N 精析完成：新增 ${completedCount} 个，复用 ${skipped} 个已有结果`);
      requestAnimationFrame(() => document.getElementById("result")?.scrollIntoView({ behavior: "smooth" }));
    } catch (reason) {
      if (!currentRun || isCurrentAnalysisRun(currentRun)) {
        activeAnalysisRun.current = null;
        setError(reason instanceof Error ? reason.message : "Top N 分析失败");
      }
    } finally {
      setBusy("");
    }
  }

  const strengths = useMemo(() => asList(analysis?.strengths), [analysis]);
  const missing = useMemo(() => asList(analysis?.missingSkills), [analysis]);
  const suggestions = useMemo(() => asList(analysis?.improvementSuggestions), [analysis]);
  const questions = useMemo(() => asList(analysis?.interviewQuestions), [analysis]);
  const evidence = useMemo(() => evidenceChunks(analysis?.retrievedContext), [analysis]);
  const ragMeta = useMemo(() => parseRagMeta(analysis?.retrievedContext), [analysis]);
  const visibleEvidence = useMemo(() => {
    if (evidenceFilter === "all") return evidence;
    if (evidenceFilter === "boost") return evidence.filter((item) => item.boost && item.boost !== "-");
    return evidence.filter((item) => item.kept);
  }, [evidence, evidenceFilter]);
  const keptEvidence = useMemo(() => evidence.filter((item) => item.kept), [evidence]);
  const parsedScore = Number(analysis?.matchScore);
  const score = Number.isFinite(parsedScore) ? parsedScore : 0;
  const analysisPending = analysis?.status === "PENDING";
  const keptCount = ragMeta?.kept ?? keptEvidence.length;
  const averageSimilarity = ragMeta?.avgSimilarity ?? (keptEvidence[0]?.similarity || 0);
  const evidenceConfidence = keptCount === 0 ? "低" : averageSimilarity >= 0.8 || keptCount >= 4 ? "高" : averageSimilarity >= 0.65 || keptCount >= 2 ? "中" : "低";
  const previewItems = annotatePreviewEvidence(
    typeof aiStatus?.minSimilarity === "number" ? aiStatus.minSimilarity : undefined,
  );
  const previewKept = previewItems.filter((item) => item.kept);
  const previewAverage = previewKept.length
    ? previewKept.reduce((sum, item) => sum + item.sim, 0) / previewKept.length
    : null;
  const previewStrengths = previewKept.filter((item) => PREVIEW_STRENGTHS[item.index]);
  const effectiveHistoryTotal = Math.max(historyTotal, history.length);
  const effectiveJobsTotal = Math.max(jobsTotal, jobs.length);
  const jobAnalysesLoading = jobAnalysesStatus === "loading";
  const jobAnalysisDataReady = Boolean(
    selectedResumeId
    && jobAnalysesStatus === "ready"
    && jobAnalysesResumeId === selectedResumeId,
  );
  const jobSemanticDataReady = Boolean(
    selectedResumeId
    && jobSemanticStatus === "ready"
    && jobSemanticResumeId === selectedResumeId,
  );
  const jobAnalysisDataRequired = jobSort === "score" || jobSort === "analyzed" || jobFilter === "unanalyzed";
  const jobSemanticDataRequired = jobSort === "semantic";
  const latestAnalysisByJob = new Map<number, AnalysisSummary>(jobAnalysisDataReady
    ? jobLatestAnalyses
        .filter((item) => item.resumeId === selectedResumeId)
        .map((item) => [item.jobDescriptionId, item])
    : []);
  const semanticSimilarityByJob = new Map<number, number>(jobSemanticDataReady
    ? jobSemanticMatches.map((item) => [item.job.id, item.similarity])
    : []);
  const visibleJobs = (() => {
    if (jobAnalysisDataRequired && !jobAnalysisDataReady) return [];
    if (jobSemanticDataRequired && !jobSemanticDataReady) return [];
    const filtered = jobFilter === "unanalyzed"
      ? jobs.filter((item) => {
          const latest = latestAnalysisByJob.get(item.id);
          return !latest || latest.status === "FAILED";
        })
      : [...jobs];
    if (jobSort === "recent") {
      filtered.sort((left, right) => jobTimestamp(right) - jobTimestamp(left) || right.id - left.id);
    } else if (jobSort === "score") {
      filtered.sort((left, right) =>
        analysisScore(latestAnalysisByJob.get(right.id)) - analysisScore(latestAnalysisByJob.get(left.id))
        || right.id - left.id,
      );
    } else if (jobSort === "semantic") {
      filtered.sort((left, right) =>
        (semanticSimilarityByJob.get(right.id) ?? -1) - (semanticSimilarityByJob.get(left.id) ?? -1)
        || right.id - left.id,
      );
    } else if (jobSort === "analyzed") {
      filtered.sort((left, right) =>
        analysisTimestamp(latestAnalysisByJob.get(right.id)) - analysisTimestamp(latestAnalysisByJob.get(left.id))
        || right.id - left.id,
      );
    }
    return filtered;
  })();
  const visibleHistory = historyExpanded ? history : history.slice(0, HISTORY_PREVIEW_LIMIT);
  const hiddenLoadedHistory = Math.max(0, history.length - HISTORY_PREVIEW_LIMIT);
  const hasMoreHistory = history.length < effectiveHistoryTotal;

  if (!token) {
    return (
      <main className="auth-shell">
        <section className="auth-story">
          <div className="brand"><span className="brand-mark">R</span><span>ResumeLens</span></div>
          <div className="eyebrow">RAG · RESUME INTELLIGENCE</div>
          <h1>让每一段经历，<br />都对准理想职位。</h1>
          <p>阿里 GTE 本地检索简历证据，{runtimeModeLabel(aiStatus)}。不是关键词打分，而是一条完整的 RAG 链路。</p>
          <div className="auth-metrics">
            <div><strong>768</strong><span>向量维度</span></div>
            <div><strong>Top-K</strong><span>证据召回</span></div>
            <div><strong>MySQL</strong><span>结果持久化</span></div>
          </div>
          <div className="story-orbit story-orbit-one" />
          <div className="story-orbit story-orbit-two" />
        </section>
        <section className="auth-panel">
          <form className="auth-card" onSubmit={submitAuth}>
            <div className="mobile-brand"><span className="brand-mark">R</span> ResumeLens</div>
            <BackendStatus variant="pill" />
            <h2>{authMode === "login" ? "登录工作台" : "创建演示账号"}</h2>
            <p>{authMode === "login" ? "继续你的简历匹配分析" : "30 秒建立自己的分析空间"}</p>
            <label>用户名<input required maxLength={64} value={auth.username} onChange={(e) => setAuth({ ...auth, username: e.target.value })} placeholder="输入用户名" /></label>
            {authMode === "register" && <>
              <label>显示名称<input required maxLength={80} value={auth.displayName} onChange={(e) => setAuth({ ...auth, displayName: e.target.value })} placeholder="例如 Arthur" /></label>
              <label>邮箱<input required type="email" value={auth.email} onChange={(e) => setAuth({ ...auth, email: e.target.value })} placeholder="name@example.com" /></label>
            </>}
            <label>密码<input required type="password" minLength={6} value={auth.password} onChange={(e) => setAuth({ ...auth, password: e.target.value })} placeholder="至少 6 位" /></label>
            {error && <div className="message error">{error}</div>}
            <button className="primary full" disabled={busy === "auth"}>{busy === "auth" ? "正在连接…" : authMode === "login" ? "进入工作台" : "注册并进入"}<span>→</span></button>
            <button className="text-button" type="button" onClick={() => { setAuthMode(authMode === "login" ? "register" : "login"); setError(""); }}>
              {authMode === "login" ? "没有账号？创建一个" : "已有账号？直接登录"}
            </button>
          </form>
        </section>
      </main>
    );
  }

  return (
    <div className={`app-shell ${sidebarCollapsed ? "sidebar-collapsed" : ""}`}>
      <aside className="sidebar">
        <div className="brand"><span className="brand-mark">R</span><span>ResumeLens</span></div>
        <button className="sidebar-toggle" onClick={toggleSidebar} title={sidebarCollapsed ? "展开侧边栏" : "收起侧边栏"} aria-label={sidebarCollapsed ? "展开侧边栏" : "收起侧边栏"}>‹</button>
        <nav>
          <a className="active" href="#workflow"><span>⌁</span><b>智能匹配</b></a>
          <a href="#result"><span>◎</span><b>分析报告</b></a>
          <a href="#history"><span>↺</span><b>历史记录</b></a>
        </nav>
        <div className="model-card">
          <span>当前模型组合</span>
          <strong>Alibaba GTE</strong>
          <small>CLS pooling · Hybrid RAG</small>
          <BackendStatus variant="model" />
        </div>
        <div className="user-card">
          <span className="avatar">{(user?.displayName || user?.username || "A").slice(0, 1).toUpperCase()}</span>
          <div><strong>{user?.displayName || user?.username}</strong><small>{user?.email}</small></div>
          <button onClick={logout} title="退出登录">↗</button>
        </div>
      </aside>

      <main className="workspace">
        <header className="topbar">
          <div><span className="eyebrow">AI RECRUITMENT COPILOT · V2</span><h1>简历 · 职位智能匹配</h1></div>
          <div className="top-actions">
            <BackendStatus />
            <button className="ghost" type="button" onClick={fillSampleForms}>填入示例</button>
            <button className="ghost accent" type="button" disabled={!!busy} onClick={() => void saveSampleAndAnalyze()}>
              {busy === "sample" || busy === "analysis" ? "示例匹配中…" : "一键示例匹配"}
            </button>
            <button className="ghost" type="button" disabled={!!busy} onClick={() => void loadWorkspace()}>刷新数据</button>
          </div>
        </header>

        <div className="overview-strip">
          <div className="overview-card"><span>已存简历</span><strong>{resumes.length}</strong><small>可在第 03 步选择</small></div>
          <div className="overview-card"><span>已存职位</span><strong>{effectiveJobsTotal}</strong><small>JD 双 Query 检索</small></div>
          <div className="overview-card"><span>分析记录</span><strong>{effectiveHistoryTotal}</strong><small>含证据链落库</small></div>
          <div className="overview-card hot"><span>检索策略</span><strong>CLS · Hybrid</strong><small>minSim {minSimilarityText(aiStatus)} · Top-K {topKText(aiStatus)}</small></div>
        </div>

        {(error || notice) && <div className={`toast ${error ? "error" : "success"}`}><span>{error ? "!" : "✓"}</span>{error || notice}<button onClick={() => { setError(""); setNotice(""); }}>×</button></div>}

        <section className="workflow" id="workflow">
          <div className="section-heading">
            <div><span>01—03</span><h2>建立匹配任务</h2></div>
            <p>表单已预填示例内容，可直接保存，或点右上角「一键示例匹配」。</p>
          </div>
          <div className="step-grid">
            <form className="step-card" onSubmit={saveResume}>
              <div className="step-title">
                <span>01</span>
                <div>
                  <h3>{editingResumeId ? `编辑简历 #${editingResumeId}` : "添加简历"}</h3>
                  <p>{editingResumeId ? "修改文本后保存 · PUT /api/resumes/{id}" : "上传文件或粘贴原文"}</p>
                </div>
              </div>
              <label className={`file-drop ${editingResumeId ? "disabled-drop" : ""}`}>
                <input type="file" accept=".pdf,.doc,.docx,.txt,.md" onChange={chooseFile} disabled={!!editingResumeId} />
                <span className="upload-icon">↑</span>
                <strong>{editingResumeId ? "编辑模式仅支持更新文本" : file ? file.name : "拖入简历文件（可选）"}</strong>
                <small>PDF · DOCX · TXT，最大 20MB</small>
              </label>
              <div className="field-row"><label>简历标题<input required value={resumeForm.title} onChange={(e) => setResumeForm({ ...resumeForm, title: e.target.value })} placeholder="Java 后端开发简历" /></label><label>候选人<input required value={resumeForm.candidateName} onChange={(e) => setResumeForm({ ...resumeForm, candidateName: e.target.value })} placeholder="姓名" /></label></div>
              <div className="field-row"><label>手机<input value={resumeForm.phone} onChange={(e) => setResumeForm({ ...resumeForm, phone: e.target.value })} placeholder="可选" /></label><label>邮箱<input type="email" value={resumeForm.email} onChange={(e) => setResumeForm({ ...resumeForm, email: e.target.value })} placeholder="可选" /></label></div>
              <label>简历文本<textarea required={!file || !!editingResumeId} rows={8} value={resumeForm.rawText} onChange={(e) => setResumeForm({ ...resumeForm, rawText: e.target.value })} disabled={!!file && !editingResumeId} placeholder={file ? "已选择文件，将由服务端解析正文；保存后可在详情中编辑" : "可直接粘贴简历正文；上传文件时由服务端解析"} /></label>
              <div className="form-actions">
                <button className="secondary" disabled={!!busy}>{busy === "resume" ? "保存中…" : editingResumeId ? "更新简历" : "保存简历"}</button>
                {editingResumeId && (
                  <button className="ghost" type="button" onClick={resetResumeEditor}>取消编辑</button>
                )}
              </div>
              <div className="entity-library">
                <div className="entity-library-head"><span>已存简历</span><em>{resumes.length}</em></div>
                {resumes.length === 0 ? (
                  <p className="entity-empty">暂无简历，保存后会出现在这里</p>
                ) : resumes.map((item) => (
                  <div className={`entity-row ${selectedResumeId === item.id ? "selected" : ""} ${editingResumeId === item.id ? "editing" : ""}`} key={item.id}>
                    <button type="button" className="entity-main" onClick={() => chooseResume(item.id)} title="选为匹配简历">
                      <strong>#{item.id} · {item.title}</strong>
                      <small>{item.candidateName}{item.originalFileName ? ` · ${item.originalFileName}` : ""}</small>
                    </button>
                    <div className="entity-actions">
                      <Link className="ghost compact" href={`/resumes/${item.id}`}>详情</Link>
                      <button type="button" className="ghost compact" onClick={() => void beginEditResume(item)} disabled={!!busy}>编辑</button>
                      <button type="button" className="ghost compact danger" onClick={() => void deleteResume(item.id)} disabled={!!busy}>删除</button>
                    </div>
                  </div>
                ))}
              </div>
            </form>

            <form className="step-card" onSubmit={saveJob}>
              <div className="step-title">
                <span>02</span>
                <div>
                  <h3>{editingJobId ? `编辑职位 #${editingJobId}` : "录入职位 JD"}</h3>
                  <p>{editingJobId ? "修改后保存 · PUT /api/job-descriptions/{id}" : "告诉 AI 目标岗位要求"}</p>
                </div>
              </div>
              <div className="field-row"><label>职位名称<input required value={jobForm.title} onChange={(e) => setJobForm({ ...jobForm, title: e.target.value })} placeholder="Java RAG 工程师" /></label><label>公司名称<input required value={jobForm.companyName} onChange={(e) => setJobForm({ ...jobForm, companyName: e.target.value })} placeholder="公司" /></label></div>
              <div className="field-row"><label>工作地点<input value={jobForm.location} onChange={(e) => setJobForm({ ...jobForm, location: e.target.value })} placeholder="杭州" /></label><label>用工类型<input value={jobForm.employmentType} onChange={(e) => setJobForm({ ...jobForm, employmentType: e.target.value })} /></label></div>
              <label>岗位描述<textarea required rows={4} value={jobForm.description} onChange={(e) => setJobForm({ ...jobForm, description: e.target.value })} placeholder="岗位职责、业务方向…" /></label>
              <label>任职要求<textarea rows={4} value={jobForm.requirements} onChange={(e) => setJobForm({ ...jobForm, requirements: e.target.value })} placeholder="技术栈、经验要求…" /></label>
              <div className="form-actions">
                <button className="secondary" disabled={!!busy}>{busy === "job" ? "保存中…" : editingJobId ? "更新职位" : "保存职位"}</button>
                {editingJobId && (
                  <button className="ghost" type="button" onClick={resetJobEditor}>取消编辑</button>
                )}
                <button className="ghost" type="button" onClick={() => setShowBulkImport((v) => !v)} disabled={!!busy}>
                  {showBulkImport ? "收起批量导入" : "批量导入"}
                </button>
              </div>
              {showBulkImport && (
                <div className="bulk-import-panel">
                  <div className="bulk-import-head">
                    <strong>批量导入 JD</strong>
                    <small>调用 POST /api/job-descriptions/import · 支持 JSON 数组 / {"{ items }"} / NDJSON</small>
                  </div>
                  <textarea
                    rows={8}
                    value={bulkImportText}
                    onChange={(e) => setBulkImportText(e.target.value)}
                    placeholder='[{"title":"...","companyName":"...","description":"..."}]'
                  />
                  <div className="form-actions">
                    <button className="secondary" type="button" disabled={!!busy} onClick={() => void bulkImportJobs()}>
                      {busy === "bulk-import" ? "导入中…" : "确认导入"}
                    </button>
                    <button className="ghost" type="button" onClick={() => setBulkImportText(JSON.stringify(SAMPLE_BULK_JOBS, null, 2))}>
                      填入示例 JSON
                    </button>
                  </div>
                </div>
              )}
              <div className="entity-library">
                <div className="entity-library-head"><span>已存职位</span><em>{effectiveJobsTotal}</em></div>
                <div className="job-library-controls">
                  <select aria-label="职位排序" value={jobSort} onChange={(event) => {
                    const next = event.target.value as JobSort;
                    setJobSort(next);
                    if (next === "score" || next === "analyzed") void loadAllJobsForRanking();
                  }} disabled={jobsLoadingMore}>
                    <option value="recent">最近保存</option>
                    <option value="semantic">向量粗排</option>
                    <option value="score">已分析匹配分</option>
                    <option value="analyzed">最近分析</option>
                  </select>
                  <label>
                    <input type="checkbox" checked={jobFilter === "unanalyzed"} disabled={jobsLoadingMore} onChange={(event) => {
                      const next = event.target.checked ? "unanalyzed" : "all";
                      setJobFilter(next);
                      if (next === "unanalyzed") void loadAllJobsForRanking();
                    }} />
                    仅看未分析
                  </label>
                </div>
                <div className="semantic-actions">
                  <select aria-label="Top N 分析数量" value={topMatchCount} onChange={(event) => setTopMatchCount(Number(event.target.value))}>
                    <option value={3}>Top 3</option>
                    <option value={5}>Top 5</option>
                    <option value={10}>Top 10</option>
                  </select>
                  <button className="ghost compact" type="button" disabled={!!busy || !selectedResumeId} onClick={() => void runTopMatchesAnalysis()}>
                    {busy === "top-analysis" ? "逐个精析中…" : "分析向量 Top N"}
                  </button>
                </div>
                <small className="semantic-note">向量相似度只负责廉价召回候选，不是最终匹配分。</small>
                {(jobAnalysesLoading || jobsLoadingMore || jobSemanticStatus === "loading") && <p className="entity-empty">正在整理完整岗位库…</p>}
                {(jobAnalysisDataRequired || jobSemanticDataRequired) && !selectedResumeId && (
                  <p className="entity-empty">请先选择一份简历，再做粗排或按分析结果整理岗位</p>
                )}
                {jobAnalysisDataRequired && jobAnalysesStatus === "error" && (
                  <div className="entity-empty">
                    匹配信息加载失败：{jobAnalysesError || "请稍后重试"}
                    {selectedResumeId && (
                      <button className="ghost compact" type="button" onClick={() => void loadJobAnalyses(Number(selectedResumeId))}>
                        重试
                      </button>
                    )}
                  </div>
                )}
                {jobSemanticDataRequired && jobSemanticStatus === "error" && (
                  <div className="entity-empty">
                    向量粗排失败：{jobSemanticError || "请稍后重试"}
                    {selectedResumeId && (
                      <button className="ghost compact" type="button" onClick={() => void loadSemanticMatches(Number(selectedResumeId))}>
                        重试
                      </button>
                    )}
                  </div>
                )}
                {jobs.length === 0 ? (
                  <p className="entity-empty">暂无职位，保存后会出现在这里</p>
                ) : (jobAnalysisDataRequired && !jobAnalysisDataReady)
                    || (jobSemanticDataRequired && !jobSemanticDataReady) ? null : visibleJobs.length === 0 ? (
                  <p className="entity-empty">当前简历下没有未分析职位</p>
                ) : visibleJobs.map((item) => {
                  const latest = latestAnalysisByJob.get(item.id);
                  const semanticSimilarity = semanticSimilarityByJob.get(item.id);
                  return (
                    <div className={`entity-row ${selectedJobId === item.id ? "selected" : ""} ${editingJobId === item.id ? "editing" : ""}`} key={item.id}>
                      <button type="button" className="entity-main job-entity-main" onClick={() => chooseJob(item.id)} title="选为匹配职位">
                        <span className="job-entity-copy">
                          <strong>#{item.id} · {item.title}</strong>
                          <small>{item.companyName}{item.location ? ` · ${item.location}` : ""}</small>
                        </span>
                        <em className={`job-score ${jobSort === "semantic" ? "semantic" : latest?.status.toLowerCase() || "unanalyzed"}`}>
                          {jobSort === "semantic" && typeof semanticSimilarity === "number"
                            ? `相似 ${semanticSimilarity.toFixed(2)}`
                            : jobScoreLabel(latest)}
                        </em>
                      </button>
                      <div className="entity-actions">
                        <Link className="ghost compact" href={`/jobs/${item.id}`}>详情</Link>
                        <button type="button" className="ghost compact" onClick={() => beginEditJob(item)} disabled={!!busy}>编辑</button>
                        <button type="button" className="ghost compact danger" onClick={() => void deleteJob(item.id)} disabled={!!busy}>删除</button>
                      </div>
                    </div>
                  );
                })}
                {jobs.length < effectiveJobsTotal && (
                  <div className="entity-load-more">
                    <button className="ghost compact" type="button" disabled={jobsLoadingMore} onClick={() => void loadMoreJobs()}>
                      {jobsLoadingMore ? "加载中…" : `继续加载（还有 ${effectiveJobsTotal - jobs.length} 个）`}
                    </button>
                  </div>
                )}
              </div>
            </form>

            <div className="step-card analyze-card">
              <div className="step-title"><span>03</span><div><h3>启动 AI 匹配</h3><p>选择数据并生成解释报告</p></div></div>
              <label>选择简历<select value={selectedResumeId} onChange={(e) => chooseResume(e.target.value ? Number(e.target.value) : "")}><option value="">请选择（先保存简历）</option>{resumes.map((item) => <option key={item.id} value={item.id}>{item.title} · {item.candidateName}</option>)}</select></label>
              <label>选择职位<select value={selectedJobId} onChange={(e) => chooseJob(e.target.value ? Number(e.target.value) : "")}><option value="">请选择（先保存职位）</option>{jobs.map((item) => <option key={item.id} value={item.id}>{item.title} · {item.companyName}</option>)}</select></label>
              <div className="rag-hint">
                <span className="badge-new">V2</span>
                <div>
                  <strong>改进检索默认开启</strong>
                  <p>GTE CLS 池化 · minSimilarity={minSimilarityText(aiStatus)} · Top-K={topKText(aiStatus)} · Hybrid + 双 Query · [chunk-N] 引用</p>
                </div>
              </div>
              <div className="pipeline">
                <div><i>1</i><span>文本分块<small>≈900 字</small></span></div>
                <b>→</b><div><i>2</i><span>CLS 向量<small>GTE 8192</small></span></div>
                <b>→</b><div><i>3</i><span>混合召回<small>阈值 · Top-K</small></span></div>
                <b>→</b><div><i>4</i><span>生成分析<small>{runtimeModeLabel(aiStatus)}</small></span></div>
              </div>
              <div className="analyze-actions">
                <button className="primary analyze" type="button" onClick={runAnalysis} disabled={!!busy || !selectedResumeId || !selectedJobId}>
                  {busy === "analysis" ? <><i className="spinner" /> AI 正在分析…</> : <>开始智能匹配 <span>↗</span></>}
                </button>
                <button className="secondary outline" type="button" disabled={!!busy} onClick={() => void saveSampleAndAnalyze()}>
                  没有数据？一键示例匹配
                </button>
              </div>
              <small className="privacy-note">简历向量在本地生成，API Key 不会进入前端。示例匹配会写入你的账号数据。</small>
            </div>
          </div>
        </section>

        <section className="results" id="result">
          <div className="section-heading">
            <div><span>REPORT</span><h2>匹配分析报告</h2></div>
            <div className="section-heading-actions">
              <p>{analysis ? `报告 #${analysis.id} · ${new Date(analysis.createdAt).toLocaleString("zh-CN")}` : "下方为版式预览，运行分析后会替换成真实结果"}</p>
              {analysis?.status === "COMPLETED" && (
                <div className="export-actions">
                  <button className="ghost" type="button" onClick={exportMarkdown}>导出 Markdown</button>
                  <button className="ghost accent" type="button" onClick={exportPdf}>导出 PDF</button>
                </div>
              )}
            </div>
          </div>
          {!analysis ? (
            <div className="report-preview">
              <div className="preview-banner">
                <span className="badge-new">PREVIEW</span>
                <div>
                  <strong>报告区预览（非真实结果）</strong>
                  <p>点右上角「一键示例匹配」，或先保存 01/02 再在 03 开始分析，这里会换成真实分数与证据。</p>
                </div>
                <button className="primary" type="button" disabled={!!busy} onClick={() => void saveSampleAndAnalyze()}>
                  {busy ? "处理中…" : "生成真实报告"}
                </button>
              </div>
              <div className="result-hero muted-hero">
                <div className="score-ring good" style={{ "--score": "295deg" } as CSSProperties}><div><strong>82</strong><span>匹配分</span></div></div>
                <div className="result-summary">
                  <span className="rating">示例 · 高度匹配</span>
                  <h3>Java 后端开发简历 <b>×</b> RAG 平台工程师</h3>
                  <p>{previewConclusion(previewItems, typeof aiStatus?.minSimilarity === "number" ? aiStatus.minSimilarity : undefined)}</p>
                  <div className="result-stats">
                    <span><strong>{previewKept.length}</strong> 个有效证据</span>
                    <span><strong>{previewItems.length - previewKept.length}</strong> 个已过滤</span>
                    <span><strong>768</strong> 维向量</span>
                    <span><strong>Hybrid</strong> 检索</span>
                  </div>
                </div>
              </div>
              <div className="diag-strip">
                <div className="diag good"><div className="k">证据可信度</div><div className="v">{previewKept.length >= 2 ? "高" : previewKept.length === 1 ? "中" : "低"}</div><div className="s">{previewKept.length} 条进入 prompt</div></div>
                <div className="diag blue"><div className="k">平均相似度</div><div className="v">{previewAverage == null ? "—" : previewAverage.toFixed(2)}</div><div className="s">仅统计保留块</div></div>
                <div className="diag"><div className="k">阈值 / Top-K</div><div className="v" style={{ fontSize: 13 }}>{minSimilarityText(aiStatus)} · K={topKText(aiStatus)}</div><div className="s">弱相关块会被过滤</div></div>
                <div className="diag warn"><div className="k">池化策略</div><div className="v" style={{ fontSize: 13 }}>CLS</div><div className="s">first token · max 8192</div></div>
              </div>
              <div className="insight-grid">
                <article className="insight strength"><header><span>✓</span><div><h3>核心优势</h3><p>可追溯 chunk</p></div></header><ul>{previewStrengths.length ? previewStrengths.map((item) => <li key={item.index}>{PREVIEW_STRENGTHS[item.index]} <span className="cite">[chunk-{item.index}]</span></li>) : <li>当前阈值下暂无过阈示例块</li>}</ul></article>
                <article className="insight gap"><header><span>△</span><div><h3>能力缺口</h3><p>证据未充分覆盖</p></div></header><ul><li>高并发调优指标描述不足</li><li>自动化测试 / CI 证据偏少</li><li>向量库运维经验未体现</li></ul></article>
                <article className="insight improve"><header><span>↗</span><div><h3>优化建议</h3><p>让简历更靠近岗位</p></div></header><ul><li>补充召回率/延迟等量化结果</li><li>写明向量存储与重建策略</li><li>增加发布流水线描述</li></ul></article>
              </div>
              <article className="evidence-panel">
                <header>
                  <div><span>RAG EVIDENCE</span><h3>检索证据链（示例）</h3></div>
                  <p>真实分析后，这里会显示简历原文分块与相似度。</p>
                </header>
                <div className="evidence-list preview-evidence">
                  {previewItems.map((item) => (
                    <details key={item.index} open={item.kept} className={item.kept ? "" : "filtered"}>
                      <summary>
                        <span>CHUNK {String(item.index).padStart(2, "0")}</span>
                        <em className="meta-chip section">{item.section}</em>
                        {item.boost && <em className="meta-chip boost">boost · {item.boost}</em>}
                        <em className={`meta-chip ${item.kept ? "kept" : "drop"}`}>{item.status}</em>
                        <strong className={item.sim >= 0.6 ? "" : item.sim >= 0.4 ? "mid" : "low"}>相似度 {(item.sim * 100).toFixed(1)}%</strong>
                        <i>⌄</i>
                      </summary>
                      <p>{item.text}</p>
                    </details>
                  ))}
                </div>
              </article>
            </div>
          ) : <>
            <div className="result-hero">
              <div className={`score-ring ${scoreTone(score)}`} style={{ "--score": `${score * 3.6}deg` } as CSSProperties}><div><strong>{analysisPending ? "…" : score.toFixed(0)}</strong><span>{analysisPending ? "分析中" : "匹配分"}</span></div></div>
              <div className="result-summary">
                <span className={`rating ${scoreTone(score)}`}>{analysisPending ? "正在生成报告" : score >= 85 ? "高度匹配" : score >= 70 ? "值得尝试" : "需要优化"}</span>
                <h3>{analysis.resumeTitle} <b>×</b> {analysis.jobTitle}</h3>
                <p>{analysisPending ? pendingAnalysisLabel(aiStatus) : analysis.summary || "已结合检索证据完成岗位匹配分析。"}</p>
                <div className="result-stats">
                  <span><strong>{ragMeta?.kept ?? keptEvidence.length}</strong> 个有效证据</span>
                  <span><strong>{ragMeta?.filtered ?? Math.max(0, evidence.length - keptEvidence.length)}</strong> 个已过滤</span>
                  <span><strong>768</strong> 维向量</span>
                  <span><strong>{ragMeta?.hybrid === false ? "语义" : "Hybrid"}</strong> 检索</span>
                </div>
              </div>
            </div>
            <div className="diag-strip">
              <div className="diag good">
                <div className="k">证据可信度</div>
                <div className="v">{evidenceConfidence}</div>
                <div className="s">{keptCount} 条进入 prompt</div>
              </div>
              <div className="diag blue">
                <div className="k">平均相似度</div>
                <div className="v">{averageSimilarity.toFixed(2)}</div>
                <div className="s">仅统计保留块</div>
              </div>
              <div className="diag">
                <div className="k">阈值 / Top-K</div>
                {/* 优先用这次分析实际生效的 rag-meta；缺失时退回服务端当前配置，而不是写死的旧值 */}
                <div className="v" style={{ fontSize: 13 }}>{typeof ragMeta?.minSimilarity === "number" ? ragMeta.minSimilarity.toFixed(2) : minSimilarityText(aiStatus)} · K={ragMeta?.topK ?? topKText(aiStatus)}</div>
                <div className="s">弱相关块会被过滤</div>
              </div>
              <div className="diag warn">
                <div className="k">池化策略</div>
                <div className="v" style={{ fontSize: 13 }}>CLS</div>
                <div className="s">first token · max 8192</div>
              </div>
            </div>
            <div className="insight-grid">
              <article className="insight strength"><header><span>✓</span><div><h3>核心优势</h3><p>尽量带 [chunk-N] 引用</p></div></header><ul>{strengths.length ? strengths.map((item, index) => <li key={index}>{renderCitedText(item)}</li>) : <li>暂无明确优势</li>}</ul></article>
              <article className="insight gap"><header><span>△</span><div><h3>能力缺口</h3><p>简历中尚未充分体现</p></div></header><ul>{missing.length ? missing.map((item, index) => <li key={index}>{item}</li>) : <li>未发现明显技能缺口</li>}</ul></article>
              <article className="insight improve"><header><span>↗</span><div><h3>优化建议</h3><p>让简历更靠近目标职位</p></div></header><ul>{suggestions.length ? suggestions.map((item, index) => <li key={index}>{item}</li>) : <li>当前简历信息较完整</li>}</ul></article>
            </div>
            {questions.length > 0 && <article className="questions"><div><span>INTERVIEW KIT</span><h3>建议准备的面试问题</h3></div><ol>{questions.map((item, index) => <li key={index}><b>{String(index + 1).padStart(2, "0")}</b>{item}</li>)}</ol></article>}
            <article className="evidence-panel">
              <header>
                <div><span>RAG EVIDENCE</span><h3>检索证据链</h3></div>
                <p>{runtimeModeLabel(aiStatus)}；分析主要基于「进入 prompt」的证据生成，可切换查看被过滤的块。</p>
              </header>
              <div className="evidence-tools">
                <button type="button" className={`filter-chip ${evidenceFilter === "kept" ? "active" : ""}`} onClick={() => setEvidenceFilter("kept")}>只看进入 prompt</button>
                <button type="button" className={`filter-chip ${evidenceFilter === "all" ? "active" : ""}`} onClick={() => setEvidenceFilter("all")}>显示全部</button>
                <button type="button" className={`filter-chip ${evidenceFilter === "boost" ? "active" : ""}`} onClick={() => setEvidenceFilter("boost")}>只看 boost</button>
              </div>
              <div className="evidence-list">
                {visibleEvidence.length ? visibleEvidence.map((item, order) => (
                  <details
                    key={`${item.index}-${item.status}`}
                    id={`chunk-${item.index}`}
                    className={item.kept ? "" : "filtered"}
                    open={order === 0 && item.kept}
                  >
                    <summary>
                      <span>CHUNK {String(item.index).padStart(2, "0")}</span>
                      <em className="meta-chip section">{item.section}</em>
                      {item.boost && item.boost !== "-" && <em className="meta-chip boost">boost · {item.boost}</em>}
                      <em className={`meta-chip ${item.kept ? "kept" : "drop"}`}>{item.kept ? "进入 prompt" : item.status}</em>
                      <strong className={item.similarity >= 0.6 ? "" : item.similarity >= 0.4 ? "mid" : "low"}>相似度 {(item.similarity * 100).toFixed(1)}%</strong>
                      <i>⌄</i>
                    </summary>
                    <p>{item.content}{typeof item.raw === "number" ? `\n\nraw=${item.raw.toFixed(4)} · status=${item.status}` : ""}</p>
                  </details>
                )) : <pre>{analysis.retrievedContext || "暂无检索证据"}</pre>}
              </div>
            </article>
          </>}
        </section>

        <section className="history" id="history">
          <div className="section-heading"><div><span>HISTORY</span><h2>最近分析</h2></div><p>{effectiveHistoryTotal} 条记录</p></div>
          <div className="history-table">
            <div className="history-row history-head"><span>报告</span><span>岗位</span><span>状态</span><span>匹配度</span><span>时间</span></div>
            {history.length === 0 ? (
              <div className="history-empty">
                暂无记录。用「一键示例匹配」生成第一条，之后每次分析都会出现在这里。
              </div>
            ) : visibleHistory.map((item) => (
              <button className="history-row" key={item.id} type="button" onClick={() => { activeAnalysisRun.current = null; setAnalysis(item); document.getElementById("result")?.scrollIntoView({ behavior: "smooth" }); }}>
                <span>#{item.id} · {item.resumeTitle}</span>
                <span>{item.jobTitle}</span>
                <span><i className={item.status.toLowerCase()} />{item.status}</span>
                <span>{item.status === "PENDING" || !Number.isFinite(Number(item.matchScore)) ? <strong>分析中</strong> : <><strong>{Number(item.matchScore).toFixed(0)}</strong> / 100</>}</span>
                <span>{new Date(item.createdAt).toLocaleDateString("zh-CN")}</span>
              </button>
            ))}
            {history.length > HISTORY_PREVIEW_LIMIT && (
              <div className="history-more">
                <button className="ghost compact" type="button" onClick={() => setHistoryExpanded((expanded) => !expanded)}>
                  {historyExpanded ? "收起" : `展开其余 ${hiddenLoadedHistory} 条`}
                </button>
                {historyExpanded && hasMoreHistory && (
                  <button className="ghost compact" type="button" disabled={historyLoadingMore} onClick={() => void loadMoreHistory()}>
                    {historyLoadingMore ? "加载中…" : `继续加载（还有 ${effectiveHistoryTotal - history.length} 条）`}
                  </button>
                )}
              </div>
            )}
          </div>
        </section>
      </main>
    </div>
  );
}
