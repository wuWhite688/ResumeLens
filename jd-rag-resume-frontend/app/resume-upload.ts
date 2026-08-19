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

export function appendResumeUploadFields(form: FormData, draft: ResumeUploadDraft) {
  Object.entries(draft).forEach(([key, value]) => {
    if (value) form.append(key, value);
  });
}
