import assert from "node:assert/strict";
import test from "node:test";
import { bookmarkKey, parseBookmarks } from "../app/workspace-bookmarks.ts";

test("bookmarks tolerate corrupt storage and retain only unique valid job ids", () => {
  assert.deepEqual(parseBookmarks(null), []);
  assert.deepEqual(parseBookmarks("broken"), []);
  assert.deepEqual(parseBookmarks('{"ids":[1]}'), []);
  assert.deepEqual(parseBookmarks('[3,3,1,"2",0,-1,1.5,null,{},9007199254740992]'), [3, 1]);
});

test("different accounts do not share a bookmark storage key", () => {
  assert.notEqual(bookmarkKey(1), bookmarkKey(2));
});
