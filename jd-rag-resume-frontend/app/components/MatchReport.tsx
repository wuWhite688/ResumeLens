"use client";

import { useEffect, useRef, useState } from "react";
import type { Analysis, Job, Resume } from "../lib/api";
import { apiRequest, getAuthSessionId, isAuthSessionCurrent, visibleApiErrorMessage } from "../lib/api";
import { asList, downloadTextFile, evidenceChunks, parseRagMeta } from "../report-export";
import { coverRequirements, coverageSummary } from "../requirement-coverage";
import { resumeFormFrom } from "../resume-upload";

type Props = {
  analysis: Analysis;
  job?: Job;
  saved: boolean;
  disabled: boolean;
  onBookmark: () => void;
  onExportMarkdown: () => void;
  onExportPdf: () => void;
  onSaveCopy: (resume: Resume) => void;
};

function citedText(text: string) {
  return text.split(/(\[chunk-\d+\])/g).map((part, index) => {
    const citation = part.match(/^\[chunk-(\d+)\]$/);
    return citation ? <a key={index} className="cite" href={`#chunk-${citation[1]}`} onClick={(event) => {
      event.preventDefault();
      const target = document.getElementById(`chunk-${citation[1]}`);
      if (target instanceof HTMLDetailsElement) {
        target.open = true;
        target.scrollIntoView({ behavior: "smooth", block: "center" });
      }
    }}>{part}</a> : part;
  });
}

/** A real report only: no fallback scores, invented evidence or generated rewrite claims. */
export function MatchReport({ analysis, job, saved, disabled, onBookmark, onExportMarkdown, onExportPdf, onSaveCopy }: Props) {
  const [tab, setTab] = useState<"evidence" | "edit" | "jd">("evidence");
  const [original, setOriginal] = useState<Resume | null>(null);
  const [reportJob, setReportJob] = useState<Job | null>(null);
  const [draft, setDraft] = useState("");
  const [draftError, setDraftError] = useState("");
  const [jobError, setJobError] = useState("");
  const [loading, setLoading] = useState(true);
  const [saving, setSaving] = useState(false);
  // Separate counters on purpose: a shared one would let the JD retry re-run the
  // resume effect, whose setDraft() would silently overwrite unsaved edits.
  const [resumeRetry, setResumeRetry] = useState(0);
  const [jobRetry, setJobRetry] = useState(0);
  const [showFiltered, setShowFiltered] = useState(false);
  const mounted = useRef(true);
  useEffect(() => {
    mounted.current = true;
    return () => { mounted.current = false; };
  }, []);
  const currentJob = job?.id === analysis.jobDescriptionId ? job : reportJob;
  const evidence = evidenceChunks(analysis.retrievedContext);
  const strengths = asList(analysis.strengths);
  const missing = asList(analysis.missingSkills);
  const suggestions = asList(analysis.improvementSuggestions);
  const questions = asList(analysis.interviewQuestions);
  const coverage = coverRequirements(currentJob?.requirements || "", evidence);
  const coverageCounts = coverageSummary(coverage);
  const meta = parseRagMeta(analysis.retrievedContext);

  useEffect(() => {
    let active = true;
    void apiRequest<Resume>(`/api/resumes/${analysis.resumeId}`).then((resume) => {
      if (!active) return;
      setOriginal(resume);
      setDraft(resume.rawText || "");
      setLoading(false);
      setDraftError("");
    }).catch((reason) => {
      if (!active) return;
      setDraftError(visibleApiErrorMessage(reason, "无法读取原简历，请重试") || "请重新登录");
      setLoading(false);
    });
    return () => { active = false; };
  }, [analysis.resumeId, resumeRetry]);

  useEffect(() => {
    if (job?.id === analysis.jobDescriptionId) return;
    let active = true;
    void apiRequest<Job>(`/api/job-descriptions/${analysis.jobDescriptionId}`).then((item) => {
      if (active) { setReportJob(item); setJobError(""); }
    }).catch(() => { if (active) setJobError("无法读取岗位原文，岗位可能已删除。"); });
    return () => { active = false; };
  }, [analysis.jobDescriptionId, job?.id, jobRetry]);

  async function saveCopy() {
    if (!original || !draft.trim() || saving) return;
    const session = getAuthSessionId();
    setSaving(true);
    setDraftError("");
    try {
      const copy = await apiRequest<Resume>("/api/resumes", {
        method: "POST",
        body: JSON.stringify({ ...resumeFormFrom(original), title: `${original.title.slice(0, 90)} · 修改版`, rawText: draft }),
      });
      if (mounted.current && isAuthSessionCurrent(session)) onSaveCopy(copy);
    } catch (reason) {
      if (mounted.current && isAuthSessionCurrent(session)) setDraftError(visibleApiErrorMessage(reason, "保存失败，草稿仍保留，可重试") || "请重新登录");
    } finally { if (mounted.current) setSaving(false); }
  }

  return <>
    <div className="review-heading"><div className="review-heading-top"><span className="eyebrow">MATCH REVIEW · 报告 #{analysis.id}</span><button type="button" className="ghost bookmark" onClick={onBookmark} aria-pressed={saved}>{saved ? "✓ 已保存" : "保存岗位"}</button></div>
      <h2>{analysis.jobTitle}</h2><p>{currentJob?.companyName}{currentJob?.location ? ` · ${currentJob.location}` : ""} · {analysis.resumeTitle}</p>
    </div>
    <div className="review-decision"><div><h3>{missing.length ? "先核对证据，再完善投递材料" : "从已有证据出发，准备下一步"}</h3><span>{analysis.matchScore != null && Number.isFinite(Number(analysis.matchScore)) ? `综合参考 ${Number(analysis.matchScore).toFixed(0)} / 100` : "暂无综合评分"}</span></div><p>{analysis.summary || "查看下方的匹配依据与修改建议。"}</p></div>
    <div className="review-tabs" role="tablist" aria-label="报告内容">{([
      ["evidence", "匹配依据"], ["edit", "修改简历"], ["jd", "岗位要求"],
    ] as const).map(([value, label], index, all) => <button key={value} id={`review-tab-${value}`} type="button" role="tab" aria-selected={tab === value} aria-controls="review-panel" onClick={() => setTab(value)} onKeyDown={(event) => {
      if (!["ArrowLeft", "ArrowRight", "Home", "End"].includes(event.key)) return;
      event.preventDefault();
      const next = event.key === "Home" ? 0 : event.key === "End" ? 2 : (index + (event.key === "ArrowRight" ? 1 : 2)) % 3;
      setTab(all[next][0]);
      document.getElementById(`review-tab-${all[next][0]}`)?.focus();
    }}>{label}</button>)}</div>
    <div className="review-body" id="review-panel" role="tabpanel" aria-labelledby={`review-tab-${tab}`}>
      {tab === "evidence" && <>
        <details className="review-evidence" open><summary><h3>有依据的优势</h3><span className="evidence-state">✓ 模型分析</span></summary><ul>{strengths.length ? strengths.map((text, index) => <li key={index}>{citedText(text)}</li>) : <li>本次报告没有给出明确优势。</li>}</ul></details>
        <details className="review-evidence" open><summary><h3>证据不足 / 尚未展示</h3><span className="evidence-state partial">◐ 待核对</span></summary><ul>{missing.length ? missing.map((text, index) => <li key={index}>{citedText(text)}</li>) : <li>本次分析未列出明确缺口，仍需结合岗位要求核对。</li>}</ul><p className="review-caveat">简历缺少证据，不代表你不具备该能力；两类缺口暂不作自动细分。</p></details>
        {coverage.length > 0 && <details className="review-evidence"><summary><h3>逐条核对岗位要求</h3><span className="review-muted">{coverageCounts.covered} / {coverageCounts.total} 词面命中</span></summary><p className="review-caveat">仅按要求与保留证据的词面重叠提示，不是模型判定；同义表述可能未命中。</p><ol className="requirement-list">{coverage.map(row => <li key={row.no}><span>{row.text}</span><span>{row.covered ? citedText(row.chunks.map(index => `[chunk-${index}]`).join(" ")) : "未找到词面证据"}</span></li>)}</ol></details>}
        <section className="review-sources"><h3>简历原文证据</h3><p className="review-caveat">引用可展开查看；相似度表示文本相关程度，不等于能力评分。</p>
          <label className="inline-check"><input type="checkbox" checked={showFiltered} onChange={event => setShowFiltered(event.target.checked)} />同时查看被过滤片段</label>
          {evidence.filter(item => item.kept || showFiltered).map(item => <details className="review-evidence source-chunk" id={`chunk-${item.index}`} key={`${item.index}-${item.status}`}><summary><span>片段 {item.index} · {item.section}</span><span className="review-muted">{item.kept ? "已引用" : "已过滤"} · {item.similarity.toFixed(2)} ⌄</span></summary><p className="source-text">{item.content}</p></details>)}
          {evidence.length === 0 && <p className="review-caveat">本次报告没有可解析的原文片段。</p>}
          <details className="review-technical"><summary>检索详情</summary><p>保留片段 {meta?.kept ?? evidence.filter(item => item.kept).length} · 阈值 {meta?.minSimilarity ?? "未记录"} · Top-K {meta?.topK ?? "未记录"}</p><pre>{analysis.retrievedContext || "暂无检索详情"}</pre></details>
        </section>
      </>}
      {tab === "edit" && <>
        <h3>让经历，说得更具体。</h3><p className="review-caveat">以当前简历原文起稿，参考分析建议自行修改。尚未自动生成改写内容。</p>
        {suggestions.length > 0 && <ul className="rewrite-suggestions">{suggestions.map((text, index) => <li key={index}>{text}</li>)}</ul>}
        {loading ? <p role="status">正在读取原简历…</p> : original ? <div className="rewrite-compare"><section><h3>当前简历原文</h3><p className="review-caveat">历史报告可能对应较早版本，请核对修改时间。</p><p className="source-text">{original.rawText || "原文为空"}</p></section><section><label htmlFor="resume-draft">修改草稿 · 可直接编辑</label><textarea id="resume-draft" rows={16} value={draft} onChange={event => setDraft(event.target.value)} disabled={saving} /></section></div> : null}
        <p className="review-caveat warning">仅补充亲自完成、能够验证的经历和数字。切换报告前请另存或下载草稿。</p>
        {draftError && <div className="message error" role="alert">{draftError}{!original && <button type="button" className="ghost" onClick={() => { setLoading(true); setResumeRetry(value => value + 1); }}>重试读取</button>}</div>}
        <div className="form-actions"><button type="button" className="primary" disabled={disabled || saving || !original || !draft.trim() || draft === original.rawText} onClick={() => void saveCopy()}>{saving ? "保存中…" : "另存为新简历"}</button><button type="button" className="ghost" disabled={!draft.trim()} onClick={() => downloadTextFile(`resume-${analysis.resumeId}-draft.txt`, draft, "text/plain;charset=utf-8")}>下载草稿</button></div>
        {questions.length > 0 && <details className="review-evidence interview-questions"><summary><h3>面试准备问题</h3><span>⌄</span></summary><ol>{questions.map((text, index) => <li key={index}>{text}</li>)}</ol></details>}
      </>}
      {tab === "jd" && <>{currentJob ? <><h3>岗位职责</h3><p className="source-text">{currentJob.description || "未提供岗位职责"}</p><h3>任职要求</h3><p className="source-text">{currentJob.requirements || "未提供任职要求"}</p></> : <p role="status">{jobError || "正在读取岗位要求…"}{jobError && <button className="ghost" onClick={() => setJobRetry(value => value + 1)}>重试</button>}</p>}</>}
    </div>
    <footer className="review-footer"><div><span className="review-muted">{new Date(analysis.createdAt).toLocaleString("zh-CN")}</span><div className="review-export"><button type="button" className="text-action" onClick={onExportMarkdown}>导出 Markdown</button><button type="button" className="text-action" onClick={onExportPdf}>导出 PDF</button></div></div>{tab !== "edit" && <button type="button" className="primary" onClick={() => setTab("edit")}>对照原文修改 →</button>}</footer>
  </>;
}
