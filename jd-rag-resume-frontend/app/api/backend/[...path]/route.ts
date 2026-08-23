const BACKEND_URL = (process.env.BACKEND_API_URL || "http://127.0.0.1:8080").replace(/\/$/, "");

const BFF_CLIENT_IP_HEADER = "x-bff-client-ip";
const CLIENT_IP_SOURCE_HEADERS = [
  "cf-connecting-ip",
  "x-vercel-forwarded-for",
  "x-forwarded-for",
  "x-real-ip",
] as const;

export function clientIpFromHeaders(headers: Headers): string | null {
  for (const name of CLIENT_IP_SOURCE_HEADERS) {
    const value = headers.get(name);
    if (!value) continue;

    const candidate = value.split(",", 1)[0].trim();
    const unwrapped = candidate.startsWith("[") && candidate.endsWith("]")
      ? candidate.slice(1, -1)
      : candidate;
    if (unwrapped.length > 0 && unwrapped.length <= 45 && /^[0-9a-f:.]+$/i.test(unwrapped)) {
      return unwrapped;
    }
  }
  return null;
}

async function proxy(request: Request, context: { params: Promise<{ path: string[] }> }) {
  const { path } = await context.params;
  const incoming = new URL(request.url);
  const target = `${BACKEND_URL}/${path.join("/")}${incoming.search}`;
  const headers = new Headers(request.headers);
  [
    "host",
    "content-length",
    "connection",
    "accept-encoding",
    BFF_CLIENT_IP_HEADER,
    ...CLIENT_IP_SOURCE_HEADERS,
  ].forEach((name) => headers.delete(name));
  const clientIp = clientIpFromHeaders(request.headers);
  if (clientIp) headers.set(BFF_CLIENT_IP_HEADER, clientIp);
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
