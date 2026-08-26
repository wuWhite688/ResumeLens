(function installResumeLensBridgeContentScript() {
  "use strict";
  if (globalThis.__resumeLensBridgeContentInstalled) return;
  globalThis.__resumeLensBridgeContentInstalled = true;

  const CHANNEL = "resumelens-browser-extension-v1";

  chrome.runtime.onMessage.addListener((message, _sender, sendResponse) => {
    if (!message || message.type !== "RESUMELENS_BRIDGE_CALL") return false;
    const requestId = requestIdentifier();
    let settled = false;

    const cleanup = () => {
      window.removeEventListener("message", onWindowMessage);
      window.clearTimeout(timer);
    };
    const finish = (response) => {
      if (settled) return;
      settled = true;
      cleanup();
      sendResponse(response);
    };
    const onWindowMessage = (event) => {
      if (event.source !== window || event.origin !== window.location.origin) return;
      const data = event.data;
      if (!data || data.channel !== CHANNEL || data.direction !== "response" || data.requestId !== requestId) return;
      finish(data.ok ? { ok: true, result: data.result } : { ok: false, error: data.error });
    };
    const timer = window.setTimeout(() => {
      finish({ ok: false, error: "ResumeLens 网页桥接超时，请刷新网页后重试" });
    }, 30_000);

    window.addEventListener("message", onWindowMessage);
    window.postMessage({
      channel: CHANNEL,
      direction: "request",
      requestId,
      action: message.action,
      payload: message.payload,
    }, window.location.origin);
    return true;
  });

  function requestIdentifier() {
    if (globalThis.crypto && typeof globalThis.crypto.randomUUID === "function") {
      return globalThis.crypto.randomUUID();
    }
    return `${Date.now()}-${Math.random().toString(16).slice(2)}`;
  }
})();
