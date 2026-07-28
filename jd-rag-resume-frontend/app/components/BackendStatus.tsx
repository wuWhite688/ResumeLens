"use client";

import { useEffect, useState } from "react";

type HealthState = "checking" | "online" | "offline";
type Variant = "model" | "pill" | "topbar";

const LABELS: Record<Variant, Record<HealthState, string>> = {
  model: {
    checking: "正在检查后端",
    online: "后端在线 · AI 按请求验证",
    offline: "后端未连接",
  },
  pill: {
    checking: "正在检查后端",
    online: "后端服务已就绪",
    offline: "后端服务未启动",
  },
  topbar: {
    checking: "检查服务中",
    online: "后端在线",
    offline: "后端离线",
  },
};

export function BackendStatus({ variant = "topbar" }: { variant?: Variant }) {
  const [state, setState] = useState<HealthState>("checking");

  useEffect(() => {
    const controller = new AbortController();

    fetch("/api/backend/actuator/health", {
      cache: "no-store",
      signal: controller.signal,
    })
      .then(async (response) => {
        if (!response.ok) return false;
        const body = (await response.json()) as { status?: string };
        return body.status === "UP";
      })
      .then((online) => setState(online ? "online" : "offline"))
      .catch((error: unknown) => {
        if ((error as { name?: string })?.name !== "AbortError") setState("offline");
      });

    return () => controller.abort();
  }, []);

  const label = LABELS[variant][state];
  if (variant === "model") {
    return <div className="model-line health-status" data-state={state}><i /><em>{label}</em></div>;
  }

  return <span className={`${variant === "pill" ? "status-pill" : "online"} health-status`} data-state={state}><i /> {label}</span>;
}
