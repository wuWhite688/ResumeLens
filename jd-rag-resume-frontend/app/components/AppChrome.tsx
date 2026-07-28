"use client";

import Link from "next/link";
import { usePathname, useRouter } from "next/navigation";
import { useEffect, useState, type ReactNode } from "react";
import {
  AUTH_EXPIRED_EVENT,
  logoutSession,
  refreshSession,
  type User,
} from "../lib/api";
import { BackendStatus } from "./BackendStatus";

type Props = {
  children: ReactNode;
  title: string;
  eyebrow?: string;
  actions?: ReactNode;
};

export function AppChrome({ children, title, eyebrow = "RESUME LENS", actions }: Props) {
  const pathname = usePathname();
  const router = useRouter();
  const [user, setUser] = useState<User | null>(null);
  const [collapsed, setCollapsed] = useState(
    () => typeof window !== "undefined" && localStorage.getItem("jd-rag-sidebar-collapsed") === "true",
  );

  useEffect(() => {
    const handleAuthExpired = () => router.replace("/");
    window.addEventListener(AUTH_EXPIRED_EVENT, handleAuthExpired);
    void refreshSession().then((session) => {
      if (session) setUser(session.user);
      else router.replace("/");
    });
    return () => window.removeEventListener(AUTH_EXPIRED_EVENT, handleAuthExpired);
  }, [router]);

  function toggleSidebar() {
    setCollapsed((current) => {
      localStorage.setItem("jd-rag-sidebar-collapsed", String(!current));
      return !current;
    });
  }

  function logout() {
    setUser(null);
    void logoutSession();
    router.replace("/");
  }

  const nav = [
    { href: "/", label: "智能匹配", icon: "⌁", match: (p: string) => p === "/" },
    { href: "/#result", label: "分析报告", icon: "◎", match: () => false },
    { href: "/#history", label: "历史记录", icon: "↺", match: () => false },
  ];

  return (
    <div className={`app-shell ${collapsed ? "sidebar-collapsed" : ""}`}>
      <aside className="sidebar">
        <div className="brand">
          <span className="brand-mark">R</span>
          <span>ResumeLens</span>
        </div>
        <button
          className="sidebar-toggle"
          onClick={toggleSidebar}
          title={collapsed ? "展开侧边栏" : "收起侧边栏"}
          aria-label={collapsed ? "展开侧边栏" : "收起侧边栏"}
          type="button"
        >
          ‹
        </button>
        <nav>
          {nav.map((item) => (
            <Link
              key={item.href}
              href={item.href}
              className={item.match(pathname) ? "active" : undefined}
            >
              <span>{item.icon}</span>
              <b>{item.label}</b>
            </Link>
          ))}
        </nav>
        <div className="model-card">
          <span>当前模型组合</span>
          <strong>Alibaba GTE</strong>
          <small>CLS pooling · Hybrid RAG</small>
          <BackendStatus variant="model" />
        </div>
        <div className="user-card">
          <span className="avatar">{(user?.displayName || user?.username || "A").slice(0, 1).toUpperCase()}</span>
          <div>
            <strong>{user?.displayName || user?.username || "访客"}</strong>
            <small>{user?.email || "未登录"}</small>
          </div>
          <button onClick={logout} title="退出登录" type="button">
            ↗
          </button>
        </div>
      </aside>

      <main className="workspace">
        <header className="topbar">
          <div>
            <span className="eyebrow">{eyebrow}</span>
            <h1>{title}</h1>
          </div>
          <div className="top-actions">
            <BackendStatus />
            {actions}
            <Link className="ghost" href="/">
              返回工作台
            </Link>
          </div>
        </header>
        {children}
      </main>
    </div>
  );
}
