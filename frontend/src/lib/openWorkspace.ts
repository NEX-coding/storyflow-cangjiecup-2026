const STORAGE_KEY = 'storyflow-open-workspace-id';

export function getOpenWorkspaceId(): number | null {
  try {
    const raw = localStorage.getItem(STORAGE_KEY);
    if (!raw) return null;
    const id = Number(raw);
    return Number.isFinite(id) && id > 0 ? id : null;
  } catch {
    return null;
  }
}

export function setOpenWorkspaceId(id: number) {
  localStorage.setItem(STORAGE_KEY, String(id));
}

export function clearOpenWorkspaceId() {
  localStorage.removeItem(STORAGE_KEY);
}
