export type ResumeUploadDraft = {
  title: string;
  candidateName: string;
  phone: string;
  email: string;
  rawText: string;
};

export function prepareResumeUploadDraft<T extends ResumeUploadDraft>(current: T, fileName: string): T {
  return {
    ...current,
    title: current.title || fileName.replace(/\.[^.]+$/, ""),
    rawText: "",
  };
}

/**
 * Multipart file uploads must treat the uploaded file as the single source of
 * resume text. Never send rawText alongside a file: a stale async text read or
 * leftover demo text must not override what the backend extracts from the file.
 */
export function appendResumeUploadFields(form: FormData, draft: ResumeUploadDraft) {
  const { rawText: _rawText, ...metadata } = draft;
  Object.entries(metadata).forEach(([key, value]) => {
    if (value) form.append(key, value);
  });
}
