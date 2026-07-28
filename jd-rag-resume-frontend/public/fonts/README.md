Self-hosted Chinese serif fonts (Noto Serif SC / Source Han Serif CN).

Files (current):
- NotoSerifSC-Regular.woff2 (font-weight: 400)
- NotoSerifSC-Bold.woff2 (font-weight: 700)

Source: Pre-optimized Chinese-simplified subsets from @fontsource/noto-serif-sc (derived from the official Adobe Source Han Serif / Google Noto Serif SC family under SIL Open Font License 1.1). See OFL.txt for full license.

Subset scope: Covers the fixed UI title strings appearing in the app (common Simplified Chinese characters used in headings like "简历 · 职位智能匹配", "匹配分析报告", "核心优势", "让每一段经历，都对准理想职位。", "登录工作台", "ResumeLens", numbers, basic Latin, and common punctuation). 

For dynamic content (e.g. user-provided resume titles, candidate names, job descriptions in reports), any characters outside this subset will fall back to the next font in the CSS stack (system Chinese serif such as "Songti SC" / "SimSun" or Georgia).

No full original large CJK fonts are kept in the repo.

@font-face uses exact matching static weights and unicode-range for efficiency.

Verification: After `npm run build`, both /fonts/NotoSerifSC-*.woff2 must return HTTP 200 with no 404 in browser console. Combined size ~2.93 MB (significantly reduced from original ~15.3 MB full files; further reduction to <1 MB total would require per-project glyph extraction of only used title chars and custom subsetting, which risks incomplete coverage for all UI strings and was not performed here due to tooling constraints in the environment).
