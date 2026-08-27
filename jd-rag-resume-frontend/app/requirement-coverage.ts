export type RequirementItem = {
  no: number;
  text: string;
};

export type RequirementCoverage = RequirementItem & {
  chunks: number[];
  terms: string[];
  score: number;
  covered: boolean;
};

export type CoverageSource = {
  index: number;
  content: string;
  kept: boolean;
};

type Terms = {
  latin: string[];
  cjk: string[];
};

const LATIN_WEIGHT = 2;
const CJK_WEIGHT = 1;
const COVER_THRESHOLD = 3;

/**
 * 只有「数字 + 分隔符 + 非数字」才当作列表序号剥掉。
 * 少了后面的 (?!\d)，"1.8s 降到 40ms" 会被削成 "8s 降到 40ms"。
 */
const LEADING_MARKER = /^\s*(?:[-–—•·]\s*|\d{1,2}\s*[.)、]\s*(?!\d))/;

const LATIN_TOKEN = /[A-Za-z][A-Za-z0-9+#]*(?:[./\-][A-Za-z0-9+#]+)*/g;
const CJK_RUN = /[一-龥]{2,}/g;

const LATIN_STOP = new Set([
  "a", "an", "and", "or", "the", "of", "to", "in", "on", "for",
  "with", "is", "are", "be", "as", "at", "by", "from", "etc",
]);

/** JD 与简历两侧都高频出现、区分不出任何东西的词，留着只会让所有要求都"命中"。 */
const CJK_STOP = new Set([
  "熟悉", "熟练", "精通", "了解", "掌握", "具备", "能够", "可以", "以上", "相关",
  "常见", "良好", "优先", "使用", "进行", "完成", "以及", "并且", "或者", "方面",
  "要求", "本科", "学历", "及以", "年及", "我们", "希望", "候选", "需要", "日常",
  "包括", "一起", "团队", "经验", "能力", "工作", "项目",
]);

export function splitRequirements(raw?: string): RequirementItem[] {
  if (!raw?.trim()) return [];
  let parts = raw.split(/\r?\n/);
  if (parts.filter((line) => line.trim()).length <= 1) {
    parts = raw.split(/[；;]/);
  }
  return parts
    .map((line) => line.replace(LEADING_MARKER, "").trim())
    .filter(Boolean)
    .map((text, offset) => ({ no: offset + 1, text }));
}

export function extractTerms(text: string): Terms {
  const latin = new Set<string>();
  for (const match of text.matchAll(LATIN_TOKEN)) {
    const token = match[0].toLowerCase().replace(/[.\-/]+$/, "");
    if (token.length > 1 && !LATIN_STOP.has(token)) latin.add(token);
  }

  const cjk = new Set<string>();
  for (const run of text.matchAll(CJK_RUN)) {
    const word = run[0];
    for (let at = 0; at + 2 <= word.length; at += 1) {
      const gram = word.slice(at, at + 2);
      if (!CJK_STOP.has(gram)) cjk.add(gram);
    }
  }

  return { latin: [...latin], cjk: [...cjk] };
}

function overlap(requirement: Terms, chunk: Terms) {
  const latinHits = requirement.latin.filter((term) => chunk.latin.includes(term));
  const cjkHits = requirement.cjk.filter((term) => chunk.cjk.includes(term));
  return {
    hits: [...latinHits, ...cjkHits],
    score: latinHits.length * LATIN_WEIGHT + cjkHits.length * CJK_WEIGHT,
  };
}

/**
 * 逐条比对 JD 要求与进入 prompt 的简历块，判断哪几段能支撑这条要求。
 * 这是词面重叠的近似匹配，不是模型判定——同义表述（"消息队列" vs "Kafka"）匹配不上，
 * 调用方在界面上必须如实标注口径。
 */
export function coverRequirements(raw: string | undefined, sources: CoverageSource[]): RequirementCoverage[] {
  const requirements = splitRequirements(raw);
  if (!requirements.length) return [];

  const chunks = sources
    .filter((item) => item.kept)
    .map((item) => ({ index: item.index, terms: extractTerms(item.content) }));

  return requirements.map((requirement) => {
    const terms = extractTerms(requirement.text);
    const scored = chunks
      .map((chunk) => ({ index: chunk.index, ...overlap(terms, chunk.terms) }))
      .filter((entry) => entry.score >= COVER_THRESHOLD)
      .sort((left, right) => right.score - left.score || left.index - right.index);

    return {
      ...requirement,
      chunks: scored.map((entry) => entry.index),
      terms: [...new Set(scored.flatMap((entry) => entry.hits))],
      score: scored.length ? scored[0].score : 0,
      covered: scored.length > 0,
    };
  });
}

export function coverageSummary(rows: RequirementCoverage[]) {
  const covered = rows.filter((row) => row.covered).length;
  return { covered, total: rows.length };
}
