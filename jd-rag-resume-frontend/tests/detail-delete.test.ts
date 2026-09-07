import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { dirname, join } from "node:path";
import test from "node:test";
import { fileURLToPath } from "node:url";

import { ApiError, AUTH_SESSION_CHANGED_CODE, visibleApiErrorMessage } from "../app/lib/api.ts";

const testsDir = dirname(fileURLToPath(import.meta.url));

const DETAIL_PAGES = [
  "../app/jobs/[id]/page.tsx",
  "../app/resumes/[id]/page.tsx",
] as const;

/**
 * Same try/catch/finally as the two detail-page remove() handlers.
 * Source tests below fail if either page drifts from this shape.
 */
async function runDetailDelete(input: {
  request: () => Promise<void>;
  setBusy: (busy: string) => void;
  setError: (error: string) => void;
  onDeleted: () => void;
}): Promise<void> {
  input.setBusy("delete");
  input.setError("");
  try {
    await input.request();
    input.onDeleted();
  } catch (reason) {
    const message = visibleApiErrorMessage(reason, "删除失败");
    if (message == null) return;
    input.setError(message);
  } finally {
    input.setBusy("");
  }
}

function trackDelete() {
  const busy: string[] = [];
  const errors: string[] = [];
  let deleted = false;
  return {
    busy,
    errors,
    wasDeleted: () => deleted,
    run: (request: () => Promise<void>) => runDetailDelete({
      request,
      setBusy: (value) => busy.push(value),
      setError: (value) => errors.push(value),
      onDeleted: () => {
        deleted = true;
      },
    }),
  };
}

function extractFunction(source: string, name: string): string {
  const start = source.indexOf(`async function ${name}(`);
  assert.ok(start >= 0, `missing async function ${name}()`);
  const brace = source.indexOf("{", start);
  assert.ok(brace >= 0, `missing body for ${name}()`);
  let depth = 0;
  for (let index = brace; index < source.length; index++) {
    const char = source[index];
    if (char === "{") depth += 1;
    else if (char === "}") {
      depth -= 1;
      if (depth === 0) return source.slice(start, index + 1);
    }
  }
  throw new Error(`unclosed ${name}()`);
}

function assertRemoveClearsBusyInFinally(source: string, page: string) {
  assert.equal(source.includes("runDetailDelete"), false, `${page} must not use runDetailDelete`);
  const remove = extractFunction(source, "remove");
  assert.match(
    remove,
    /visibleApiErrorMessage\(reason, "删除失败"\)/,
    `${page} remove() must silent-filter AUTH_SESSION_CHANGED`,
  );
  assert.match(
    remove,
    /finally \{\s*setBusy\(""\);\s*\}/,
    `${page} remove() must clear busy in finally`,
  );
  const catchBlock = remove.match(/catch \(reason\) \{[\s\S]*?\n    \}/);
  assert.ok(catchBlock, `${page} remove() must have a catch`);
  assert.equal(
    /setBusy\(/.test(catchBlock[0]),
    false,
    `${page} remove() must not clear busy inside catch`,
  );
}

test("job and resume detail remove() clear busy in finally, matching load/save", () => {
  for (const relative of DETAIL_PAGES) {
    const source = readFileSync(join(testsDir, relative), "utf8");
    assertRemoveClearsBusyInFinally(source, relative);
  }
});

test("AUTH_SESSION_CHANGED delete stays silent and still clears busy", async () => {
  const tracked = trackDelete();
  await tracked.run(async () => {
    throw new ApiError(AUTH_SESSION_CHANGED_CODE, "登录状态已变更，原请求已作废", 401);
  });

  assert.equal(tracked.wasDeleted(), false);
  assert.deepEqual(tracked.errors, [""]);
  assert.equal(tracked.errors.includes("登录状态已变更，原请求已作废"), false);
  assert.deepEqual(tracked.busy, ["delete", ""]);
});

test("ordinary delete failure still shows the original error and clears busy", async () => {
  const tracked = trackDelete();
  await tracked.run(async () => {
    throw new ApiError("CONFLICT", "职位仍被分析记录引用", 409);
  });

  assert.equal(tracked.wasDeleted(), false);
  assert.deepEqual(tracked.errors, ["", "职位仍被分析记录引用"]);
  assert.deepEqual(tracked.busy, ["delete", ""]);
});

test("successful delete navigates after busy is armed and then cleared", async () => {
  const tracked = trackDelete();
  await tracked.run(async () => undefined);

  assert.equal(tracked.wasDeleted(), true);
  assert.deepEqual(tracked.errors, [""]);
  assert.deepEqual(tracked.busy, ["delete", ""]);
});
