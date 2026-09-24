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
    return String(raw).split(/\r?\n|[；;]/).map((item) => item.replace(/^[-*•\d.、\s]+/, "").trim()).filter(Boolean);
  }

  return { asList };
});
