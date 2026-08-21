const BACKEND_URL = (process.env.BACKEND_API_URL || "http://127.0.0.1:8080").replace(/\/$/, "");

async function proxy(request: Request, context: { params: Promise<{ path: string[] }> }) {
  const { path } = await context.params;
  const incoming = new URL(request.url);
  const target = `${BACKEND_URL}/${path.join("/")}${incoming.search}`;
  const headers = new Headers(request.headers);
  ["host", "content-length", "connection", "accept-encoding"].forEach((name) => headers.delete(name));
  const body = request.method === "GET" || request.method === "HEAD" ? undefined : await request.arrayBuffer();

  try {
    const upstream = await fetch(target, { method: request.method, headers, body, redirect: "manual" });
    const responseHeaders = new Headers(upstream.headers);
    ["content-length", "content-encoding", "connection"].forEach((name) => responseHeaders.delete(name));
    const setCookie = responseHeaders.get("set-cookie");
    if (setCookie) {
      responseHeaders.set("set-cookie", rewriteUpstreamCookie(setCookie, incoming));
    }
    return new Response(upstream.body, { status: upstream.status, headers: responseHeaders });
  } catch {
    return Response.json({ success: false, code: "BACKEND_UNAVAILABLE", message: "后端未启动，请先启动 8080 服务", data: null }, { status: 503 });
  }
}

export function rewriteUpstreamCookie(setCookie: string, incoming: URL) {
  let cookie = setCookie.replace(/Path=\/api\/auth(?=;|$)/i, "Path=/api/backend/api/auth");
  if (incoming.protocol === "https:" && !/;\s*Secure/i.test(cookie)) {
    cookie += "; Secure";
  }
  return cookie;
}

export const GET = proxy;
export const POST = proxy;
export const PUT = proxy;
export const DELETE = proxy;
