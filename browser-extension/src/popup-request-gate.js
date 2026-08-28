(function exposePopupRequestGate(root, factory) {
  const requestGate = factory();
  if (typeof module === "object" && module.exports) module.exports = requestGate;
  root.ResumeLensPopupRequestGate = requestGate;
})(typeof globalThis !== "undefined" ? globalThis : this, function createPopupRequestGateModule() {
  "use strict";

  function create() {
    let generation = 0;

    return {
      begin(resumeId) {
        generation += 1;
        return Object.freeze({ generation, resumeId: Number(resumeId) });
      },
      cancel() {
        generation += 1;
      },
      isCurrent(token, resumeId) {
        return Boolean(token)
          && token.generation === generation
          && token.resumeId === Number(resumeId);
      },
    };
  }

  return { create };
});
