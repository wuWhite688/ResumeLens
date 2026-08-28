const assert = require("node:assert/strict");
const fs = require("node:fs");
const path = require("node:path");
const test = require("node:test");

const manifest = JSON.parse(fs.readFileSync(path.join(__dirname, "..", "manifest.json"), "utf8"));

test("BOSS access is temporary and granted only after clicking the extension", () => {
  assert.ok(manifest.permissions.includes("activeTab"));
  assert.ok(manifest.permissions.includes("scripting"));
  assert.equal(manifest.host_permissions.some((value) => value.includes("zhipin.com")), false);
  assert.equal(
    manifest.content_scripts.some((script) => script.matches.some((value) => value.includes("zhipin.com"))),
    false,
  );
});
