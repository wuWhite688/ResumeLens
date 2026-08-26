(function installBossContentScript() {
  "use strict";
  if (globalThis.__resumeLensBossContentInstalled) return;
  globalThis.__resumeLensBossContentInstalled = true;

  chrome.runtime.onMessage.addListener((message, _sender, sendResponse) => {
    if (!message || message.type !== "RESUMELENS_EXTRACT_BOSS_JOB") return false;
    try {
      const extractor = globalThis.ResumeLensBossExtractor;
      if (!extractor) throw new Error("岗位抓取器没有加载，请刷新 BOSS 页面");
      sendResponse({ ok: true, ...extractor.extract(document, window.location.href) });
    } catch (reason) {
      sendResponse({
        ok: false,
        error: reason instanceof Error ? reason.message : "读取 BOSS 岗位失败",
      });
    }
    return false;
  });
})();
