(function exposeReportList(root, factory) {
  const reportList = factory();
  if (typeof module === "object" && module.exports) module.exports = reportList;
  root.ResumeLensReportList = reportList;
})(typeof globalThis !== "undefined" ? globalThis : this, function createReportListModule() {
  "use strict";

  function asList(raw) {
    if (!raw) return [];
    try {
      const parsed = JSON.parse(raw);
      if (Array.isArray(parsed)) return parsed.map(String).filter(Boolean);
    } catch {
      // Plain text and newline-delimited model output are both supported.
    }
    // 与前端 app/report-export.ts 的 asList 同一规则：只去项目符号和「数字 + . ) 、」序号，
    // 序号后不能紧跟数字（"1.8s" 不是序号），裸数字开头（"5 年经验"）是正文。
    return String(raw).split(/\r?\n|[；;]/)
      .map((item) => item.replace(/^\s*(?:[-*•]\s*)?(?:\d+[.)、](?!\d)\s*)?/, "").trim())
      .filter(Boolean);
  }

  return { asList };
});
