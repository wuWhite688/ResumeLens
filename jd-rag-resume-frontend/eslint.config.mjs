import { defineConfig, globalIgnores } from "eslint/config";
import nextVitals from "eslint-config-next/core-web-vitals";
import nextTs from "eslint-config-next/typescript";

const eslintConfig = defineConfig([
  ...nextVitals,
  ...nextTs,
  // Override default ignores of eslint-config-next.
  globalIgnores([
    // Default ignores of eslint-config-next:
    ".next/**",
    "out/**",
    "build/**",
    "next-env.d.ts",
  ]),
  // 对象 rest 解构是「刻意丢弃字段」的惯用法：`appendResumeUploadFields` 就靠它
  // 把 rawText 排除出 metadata，避免陈旧文本随文件一起上传（tests/resume-upload.test.ts
  // 有用例锁住该行为）。被排除的那个绑定必然「未使用」，但删掉它会改变 rest 的内容，
  // 属于改行为而不是清理 —— 所以让规则理解这一惯用法，而不是逐处加 eslint-disable。
  // eslint-config-next 只给这条规则设了严重级别、未设选项，因此这里仅补 ignoreRestSiblings，
  // 其余选项保持规则默认；真正未使用的变量照常报告。
  {
    rules: {
      "@typescript-eslint/no-unused-vars": ["warn", { ignoreRestSiblings: true }],
    },
  },
]);

export default eslintConfig;
