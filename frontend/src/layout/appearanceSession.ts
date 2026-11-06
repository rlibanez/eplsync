import type { Palette } from "./Appearance";
export interface Appearance {
  scheme: "light" | "dark";
  palette: Palette;
}
export interface StoredAppearance {
  userId: string;
  scheme: Appearance["scheme"] | null;
  palette: Palette | null;
}
export type AppearanceRequest = (
  method: "GET" | "PUT",
  body: unknown,
  signal: AbortSignal,
) => Promise<StoredAppearance>;

/** One session owns its requests; serialized saves cannot reorder rapid edits. */
export class AppearanceSession {
  private abort = new AbortController();
  private active = true;
  private ready = false;
  private loading = false;
  private saving = false;
  private revision = 0;
  private savedRevision = 0;
  private pending: Partial<Appearance> = {};
  private initialize = false;
  constructor(
    private userId: string,
    private value: Appearance,
    private request: AppearanceRequest,
    private apply: (value: Appearance) => void,
    private error: (failed: boolean) => void,
  ) {}
  dispose() {
    this.active = false;
    this.abort.abort();
  }
  async load() {
    if (this.loading || !this.active) return;
    this.loading = true;
    try {
      const stored = await this.request("GET", undefined, this.abort.signal);
      if (!this.active) return;
      if (stored.userId !== this.userId) throw new Error("Session changed");
      const exists = stored.scheme !== null && stored.palette !== null;
      const base = exists
        ? { scheme: stored.scheme!, palette: stored.palette! }
        : this.value;
      this.value = { ...base, ...this.pending };
      this.initialize = !exists && this.revision === 0;
      this.ready = true;
      this.apply(this.value);
      this.error(false);
      if (!exists || this.revision > 0) {
        this.revision++;
        void this.flush();
      }
    } catch {
      if (this.active) this.error(true);
    } finally {
      this.loading = false;
    }
  }
  set(patch: Partial<Appearance>) {
    this.value = { ...this.value, ...patch };
    this.pending = { ...this.pending, ...patch };
    this.revision++;
    this.apply(this.value);
    if (this.ready) void this.flush();
    else void this.load();
  }
  private async flush() {
    if (this.saving || !this.active) return;
    this.saving = true;
    try {
      while (this.active && this.savedRevision < this.revision) {
        const revision = this.revision;
        const stored = await this.request(
          "PUT",
          { userId: this.userId, ...this.value, initialize: this.initialize },
          this.abort.signal,
        );
        if (!this.active) return;
        if (stored.userId !== this.userId || !stored.scheme || !stored.palette)
          throw new Error("Session changed");
        this.initialize = false;
        this.savedRevision = revision;
        if (revision === this.revision) {
          this.value = { scheme: stored.scheme, palette: stored.palette };
          this.apply(this.value);
        }
        this.error(false);
      }
    } catch {
      if (this.active) this.error(true);
    } finally {
      this.saving = false;
    }
  }
}
