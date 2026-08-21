import assert from "node:assert/strict";
import test from "node:test";
import {
  apiRequest,
  clearAuthSession,
  refreshSession,
  setAccessToken,
} from "../app/lib/api.ts";
import { POST as proxyPost, rewriteUpstreamCookie } from "../app/api/backend/[...path]/route.ts";

const session = {
  tokenType: "Bearer" as const,
  accessToken: "fresh-access-token",
  expiresInSeconds: 900,
  user: { id: 1, username: "arthur", displayName: "Arthur", email: "arthur@example.com" },
};

test.afterEach(() => {
  clearAuthSession();
});

test("a 401 refreshes once and retries with the new in-memory token", async () => {
  setAccessToken("expired-access-token");
  const calls: Array<{ url: string; authorization: string | null }> = [];
  const responses = [
    Response.json({ success: false, code: "UNAUTHORIZED", message: "expired", data: null }, { status: 401 }),
    Response.json({ success: true, code: "OK", message: "success", data: session }),
    Response.json({ success: true, code: "OK", message: "success", data: { value: 42 } }),
  ];

  globalThis.fetch = async (input, init) => {
    calls.push({
      url: String(input),
      authorization: new Headers(init?.headers).get("Authorization"),
    });
    return responses.shift()!;
  };

  const result = await apiRequest<{ value: number }>("/api/protected");

  assert.deepEqual(result, { value: 42 });
  assert.equal(calls.length, 3);
  assert.equal(calls[0].authorization, "Bearer expired-access-token");
  assert.equal(calls[1].url, "/api/backend/api/auth/refresh");
  assert.equal(calls[2].authorization, "Bearer fresh-access-token");
});

test("parallel refresh calls share one browser request", async () => {
  let calls = 0;
  globalThis.fetch = async () => {
    calls += 1;
    await Promise.resolve();
    return Response.json({ success: true, code: "OK", message: "success", data: session });
  };

  const [first, second] = await Promise.all([refreshSession(), refreshSession()]);

  assert.equal(calls, 1);
  assert.equal(first?.accessToken, "fresh-access-token");
  assert.equal(second?.accessToken, "fresh-access-token");
});

test("a successful 204 response resolves without trying to parse an envelope", async () => {
  globalThis.fetch = async () => new Response(null, { status: 204 });

  const result = await apiRequest<void>("/api/resumes/1", { method: "DELETE" });

  assert.equal(result, undefined);
});

test("the backend proxy rewrites the refresh cookie path for the browser route", async () => {
  globalThis.fetch = async () => new Response("{}", {
    status: 200,
    headers: {
      "Content-Type": "application/json",
      "Set-Cookie": "jd-rag-refresh=opaque; Path=/api/auth; HttpOnly; SameSite=Lax",
    },
  });
  const request = new Request("http://localhost/api/backend/api/auth/login", {
    method: "POST",
    body: "{}",
    headers: { "Content-Type": "application/json" },
  });

  const response = await proxyPost(request, {
    params: Promise.resolve({ path: ["api", "auth", "login"] }),
  });

  assert.match(
    response.headers.get("set-cookie") || "",
    /Path=\/api\/backend\/api\/auth(?:;|$)/,
  );
});

test("the backend proxy adds Secure on HTTPS but leaves local HTTP cookies intact", () => {
  const cookie = "jd-rag-refresh=opaque; Path=/api/auth; HttpOnly; SameSite=Lax";
  const httpCookie = rewriteUpstreamCookie(cookie, new URL("http://127.0.0.1:3000/api/backend/api/auth/login"));
  const httpsCookie = rewriteUpstreamCookie(cookie, new URL("https://example.com/api/backend/api/auth/login"));
  assert.equal(httpCookie.includes("Secure"), false);
  assert.match(httpsCookie, /;\s*Secure/);
});
