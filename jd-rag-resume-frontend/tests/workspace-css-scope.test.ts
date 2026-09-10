import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { dirname, join } from "node:path";
import test from "node:test";
import { fileURLToPath } from "node:url";

const testsDir = dirname(fileURLToPath(import.meta.url));

/** Comments may legitimately mention :root; only real rules are checked. */
function withoutComments(css: string): string {
  return css.replace(/\r\n/g, "\n").replace(/\/\*[\s\S]*?\*\//g, "");
}

const workspaceCss = withoutComments(readFileSync(join(testsDir, "../app/workspace.css"), "utf8"));
const globalsCss = withoutComments(readFileSync(join(testsDir, "../app/globals.css"), "utf8"));

/**
 * workspace.css is imported from the root layout, so anything it puts on :root
 * also repaints the sign-in page, which is not wrapped in .refined-workspace.
 */
test("工作台样式不在 :root 上重定义全站配色变量", () => {
  const rootRules = [...workspaceCss.matchAll(/:root[^{]*\{[^}]*\}/g)].map((match) => match[0]);
  assert.deepEqual(
    rootRules,
    [],
    "workspace.css 里的变量要写在 .refined-workspace 上，否则会漏到登录页",
  );
});

test("被工作台覆盖的变量在 .refined-workspace 作用域内声明", () => {
  const overridden = ["--ink", "--muted", "--paper", "--panel", "--line", "--mint-dark"];
  const scoped = workspaceCss.match(/\.refined-workspace \{([^}]*--[^}]*)\}/);
  assert.ok(scoped, "找不到 .refined-workspace 上的变量声明");

  for (const name of overridden) {
    assert.match(globalsCss, new RegExp(`${name}:`), `${name} 应当由 globals.css 提供全站默认值`);
    assert.match(scoped[1], new RegExp(`${name}:`), `${name} 的工作台取值应当收在 .refined-workspace 内`);
  }
});

test("工作台自绘底色，不依赖 body 上的全局 --paper", () => {
  assert.match(globalsCss, /body \{[^}]*background: var\(--paper\)/);
  assert.match(workspaceCss, /\.refined-workspace \{[^}]*background: var\(--paper\)/);
});
