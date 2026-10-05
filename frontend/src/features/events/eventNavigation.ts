const key = "eplsync.events.navigation";
export interface EventNavigation {
  action: string | null;
  category: string | null;
  outcome: string | null;
  origin: string | null;
  from: string;
  to: string;
  page: number;
  scroll: number;
  expanded: string[];
}
const defaults: EventNavigation = {
  action: null,
  category: null,
  outcome: null,
  origin: null,
  from: "",
  to: "",
  page: 0,
  scroll: 0,
  expanded: [],
};
let memory = defaults;
export function readEventNavigation(): EventNavigation {
  try {
    const parsed = JSON.parse(sessionStorage.getItem(key) || "null");
    if (parsed) {
      memory = { ...defaults, ...parsed };
      if (!Number.isSafeInteger(memory.page) || memory.page < 0)
        memory.page = 0;
      if (!Array.isArray(memory.expanded)) memory.expanded = [];
    }
  } catch {
    /* Storage is optional. */
  }
  return memory;
}
export function saveEventNavigation(value: EventNavigation) {
  memory = value;
  try {
    sessionStorage.setItem(key, JSON.stringify(value));
  } catch {
    /* Storage is optional. */
  }
}
