import { apiRequest } from "./lib/api";
import {
  EXTENSION_BRIDGE_CHANNEL,
  dispatchExtensionBridgeRequest,
  isExtensionBridgeRequest,
} from "./extension-bridge-core";

type ExtensionBridgeResponse = {
  channel: typeof EXTENSION_BRIDGE_CHANNEL;
  direction: "response";
  requestId: string;
  ok: boolean;
  result?: unknown;
  error?: string;
};

export function installResumeLensExtensionBridge(target: Window = window) {
  const listener = (event: MessageEvent) => {
    if (event.source !== target || event.origin !== target.location.origin) return;
    if (!isExtensionBridgeRequest(event.data)) return;

    const request = event.data;
    void dispatchExtensionBridgeRequest(request, apiRequest)
      .then((result) => postResponse(target, request.requestId, { ok: true, result }))
      .catch((reason) => postResponse(target, request.requestId, {
        ok: false,
        error: reason instanceof Error ? reason.message : "ResumeLens 请求失败",
      }));
  };

  target.addEventListener("message", listener);
  return () => target.removeEventListener("message", listener);
}

function postResponse(
  target: Window,
  requestId: string,
  response: Pick<ExtensionBridgeResponse, "ok" | "result" | "error">,
) {
  target.postMessage({
    channel: EXTENSION_BRIDGE_CHANNEL,
    direction: "response",
    requestId,
    ...response,
  } satisfies ExtensionBridgeResponse, target.location.origin);
}
