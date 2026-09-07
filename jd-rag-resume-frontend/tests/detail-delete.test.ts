import assert from "node:assert/strict";
import test from "node:test";

import { ApiError, AUTH_SESSION_CHANGED_CODE, runDetailDelete } from "../app/lib/api.ts";

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
