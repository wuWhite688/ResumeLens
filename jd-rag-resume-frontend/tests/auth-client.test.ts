import assert from "node:assert/strict";
import test from "node:test";
import {
  apiRequest,
  clearAuthSession,
  logoutSession,
  refreshSession,
  setAccessToken,
} from "../app/lib/api.ts";
import {
  POST as proxyPost,
  clientIpFromHeaders,
  rewriteUpstreamCookie,
} from "../app/api/backend/[...path]/route.ts";

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

test("login waits for bootstrap refresh so its Set-Cookie response wins", async () => {
  let releaseRefresh!: () => void;
  const refreshGate = new Promise<void>((resolve) => {
    releaseRefresh = resolve;
  });
  const loginSession = {
    ...session,
    accessToken: "new-login-token",
    user: { ...session.user, id: 2, username: "new-user" },
  };
  let protectedAuthorization = "";
  globalThis.fetch = async (input, init) => {
    const url = String(input);
    if (url.endsWith("/api/auth/refresh")) {
      await refreshGate;
      return Response.json({ success: true, code: "OK", message: "success", data: session });
    }
    if (url.endsWith("/api/auth/login")) {
      return Response.json({ success: true, code: "OK", message: "success", data: loginSession });
    }
    protectedAuthorization = new Headers(init?.headers).get("Authorization") || "";
    return Response.json({ success: true, code: "OK", message: "success", data: { ok: true } });
  };

  const bootstrap = refreshSession();
  const loginPromise = apiRequest<AuthResponse>(
    "/api/auth/login",
    { method: "POST", body: JSON.stringify({ username: "new-user", password: "secret" }) },
    { auth: false },
  );
  await Promise.resolve();
  releaseRefresh();
  assert.equal((await bootstrap)?.accessToken, "fresh-access-token");
  const login = await loginPromise;
  setAccessToken(login.accessToken);

  await apiRequest<{ ok: boolean }>("/api/protected");
  assert.equal(protectedAuthorization, "Bearer new-login-token");
});

test("a stale refresh cannot overwrite a newer explicitly installed token", async () => {
  let releaseRefresh!: () => void;
  const refreshGate = new Promise<void>((resolve) => {
    releaseRefresh = resolve;
  });
  let protectedAuthorization = "";
  globalThis.fetch = async (input, init) => {
    if (String(input).endsWith("/api/auth/refresh")) {
      await refreshGate;
      return Response.json({ success: true, code: "OK", message: "success", data: session });
    }
    protectedAuthorization = new Headers(init?.headers).get("Authorization") || "";
    return Response.json({ success: true, code: "OK", message: "success", data: { ok: true } });
  };

  const staleRefresh = refreshSession();
  setAccessToken("explicit-new-token");
  releaseRefresh();

  assert.equal(await staleRefresh, null);
  await apiRequest<{ ok: boolean }>("/api/protected");
  assert.equal(protectedAuthorization, "Bearer explicit-new-token");
});

test("a new login waits for an in-flight logout response", async () => {
  let releaseLogout!: () => void;
  const logoutGate = new Promise<void>((resolve) => {
    releaseLogout = resolve;
  });
  const calls: string[] = [];
  globalThis.fetch = async (input) => {
    const url = String(input);
    calls.push(url);
    if (url.endsWith("/api/auth/logout")) {
      await logoutGate;
      return Response.json({ success: true, code: "OK", message: "success", data: null });
    }
    return Response.json({ success: true, code: "OK", message: "success", data: session });
  };

  const logout = logoutSession();
  const login = apiRequest<AuthResponse>(
    "/api/auth/login",
    { method: "POST", body: JSON.stringify({ username: "arthur", password: "secret" }) },
    { auth: false },
  );
  await Promise.resolve();

  assert.deepEqual(calls, ["/api/backend/api/auth/logout"]);
  releaseLogout();
  await logout;
  assert.equal((await login).accessToken, "fresh-access-token");
  assert.deepEqual(calls, [
    "/api/backend/api/auth/logout",
    "/api/backend/api/auth/login",
  ]);
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

test("the backend proxy replaces caller-supplied client identity with the edge address", async () => {
  let forwardedHeaders = new Headers();
  globalThis.fetch = async (_input, init) => {
    forwardedHeaders = new Headers(init?.headers);
    return new Response("{}", { status: 200, headers: { "Content-Type": "application/json" } });
  };
  const request = new Request("http://localhost/api/backend/api/auth/register", {
    method: "POST",
    body: "{}",
    headers: {
      "Content-Type": "application/json",
      "CF-Connecting-IP": "203.0.113.43",
      "X-Forwarded-For": "198.51.100.8, 198.51.100.9",
      "X-BFF-Client-IP": "192.0.2.99",
    },
  });

  await proxyPost(request, {
    params: Promise.resolve({ path: ["api", "auth", "register"] }),
  });

  assert.equal(clientIpFromHeaders(request.headers), "203.0.113.43");
  assert.equal(forwardedHeaders.get("x-bff-client-ip"), "203.0.113.43");
  assert.equal(forwardedHeaders.has("cf-connecting-ip"), false);
  assert.equal(forwardedHeaders.has("x-forwarded-for"), false);
});

test("the backend proxy adds Secure on HTTPS but leaves local HTTP cookies intact", () => {
  const cookie = "jd-rag-refresh=opaque; Path=/api/auth; HttpOnly; SameSite=Lax";
  const httpCookie = rewriteUpstreamCookie(cookie, new URL("http://127.0.0.1:3000/api/backend/api/auth/login"));
  const httpsCookie = rewriteUpstreamCookie(cookie, new URL("https://example.com/api/backend/api/auth/login"));
  assert.equal(httpCookie.includes("Secure"), false);
  assert.match(httpsCookie, /;\s*Secure/);
});
