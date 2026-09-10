/** Only job ids are stored. Resume content and authentication never enter browser storage. */
export function bookmarkKey(userId: number) {
  return `resumelens:bookmarks:${userId}`;
}

export function parseBookmarks(value: string | null): number[] {
  try {
    const parsed: unknown = JSON.parse(value || "[]");
    return Array.isArray(parsed)
      ? [...new Set(parsed.filter((id): id is number => typeof id === "number" && Number.isSafeInteger(id) && id > 0))]
      : [];
  } catch { return []; }
}
