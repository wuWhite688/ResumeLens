import assert from "node:assert/strict";
import test from "node:test";

import { GET, POST } from "../app/api/backend/[...path]/route.ts";

type Handler = typeof POST;

async function proxyThrough(
  handler: Handler,
  method: string,
  headers: Record<string, string>,
  path: string[] = ["api", "auth", "refresh"],
) {
  const originalFetch = globalThis.fetch;
  const forwarded: Headers[] = [];
  globalThis.fetch = (async (_url: string | URL | Request, init?: RequestInit) => {
    forwarded.push(new Headers(init?.headers));
    return new Response("{}", { status: 200, headers: { "content-type": "application/json" } });
  }) as typeof fetch;
  try {
    const request = new Request(`http://localhost:3000/api/backend/${path.join("/")}`, {
      method,
      headers,
      body: method === "GET" ? undefined : "{}",
    });
    const response = await handler(request, { params: Promise.resolve({ path }) });
    return { response, forwarded };
  } finally {
    globalThis.fetch = originalFetch;
  }
}

// refresh cookie 由浏览器自动携带。SameSite=Lax 按「站点」判断，兄弟子域发起的 POST
// 仍会带上它，所以写请求必须同源，同站也不行。
for (const site of ["cross-site", "same-site"]) {
  test(`backend proxy blocks a ${site} write before it reaches the backend`, async () => {
    const { response, forwarded } = await proxyThrough(POST, "POST", {
      cookie: "jd-rag-refresh=victim",
      "sec-fetch-site": site,
      origin: "https://sibling.example.com",
    });

    assert.equal(response.status, 403);
    assert.equal((await response.json()).code, "CROSS_SITE_REQUEST_BLOCKED");
    assert.equal(forwarded.length, 0);
  });
}

test("backend proxy falls back to Origin when the browser sends no fetch metadata", async () => {
  const foreign = await proxyThrough(POST, "POST", { origin: "https://evil.example.com" });
  assert.equal(foreign.response.status, 403);
  assert.equal(foreign.forwarded.length, 0);

  const opaque = await proxyThrough(POST, "POST", { origin: "null" });
  assert.equal(opaque.response.status, 403);
  assert.equal(opaque.forwarded.length, 0);

  const own = await proxyThrough(POST, "POST", { origin: "http://localhost:3000" });
  assert.equal(own.response.status, 200);
  assert.equal(own.forwarded.length, 1);
});

test("backend proxy forwards same-origin writes with their fetch metadata", async () => {
  const { response, forwarded } = await proxyThrough(POST, "POST", {
    "sec-fetch-site": "same-origin",
    origin: "http://localhost:3000",
  });

  assert.equal(response.status, 200);
  assert.equal(forwarded.length, 1);
  // 后端还有第二道同样的校验，前提是 BFF 把浏览器给的值原样带过去。
  assert.equal(forwarded[0].get("sec-fetch-site"), "same-origin");
  assert.equal(forwarded[0].get("origin"), "http://localhost:3000");
});

test("backend proxy leaves reads and non-browser clients alone", async () => {
  const crossSiteRead = await proxyThrough(GET, "GET", { "sec-fetch-site": "cross-site" }, ["api", "resumes"]);
  assert.equal(crossSiteRead.response.status, 200);
  assert.equal(crossSiteRead.forwarded.length, 1);

  const script = await proxyThrough(POST, "POST", {}, ["api", "auth", "login"]);
  assert.equal(script.response.status, 200);
  assert.equal(script.forwarded.length, 1);
});
