import { test as base, expect, type Page } from "@playwright/test";
export { expect };
export type { Page } from "@playwright/test";
// UI tests isolate the transport. Real SSE commit/replay is covered by EventApiTests.
export const test = base.extend<{ eventTransport: void }>({
  eventTransport: [
    async ({ page, context }, use, testInfo) => {
      const unmocked: string[] = [];
      // Block access to real backends. More specific test routes take precedence.
      await context.route(
        (url) => url.pathname.startsWith("/api/"),
        async (route) => {
          const request = route.request();
          unmocked.push(
            `${request.method()} ${new URL(request.url()).pathname}${new URL(request.url()).search}`,
          );
          await route.fulfill({
            status: 501,
            json: { code: "E2E_UNMOCKED_API" },
          });
        },
      );
      await context.route(
        /\/api\/catalog\/books\/\d+\/history(?:\?|$)/,
        (route) => {
          if (route.request().method() !== "GET") return route.fallback();
          const params = new URL(route.request().url()).searchParams;
          return route.fulfill({
            json: {
              items: [],
              meta: {
                page: Number(params.get("page") ?? 0),
                size: Number(params.get("size") ?? 20),
                totalItems: 0,
                totalPages: 0,
                hasNext: false,
                hasPrevious: false,
              },
            },
          });
        },
      );
      await context.route("**/api/application/updates", (route) =>
        route.request().method() === "GET"
          ? route.fulfill({
              json: {
                automatic: false,
                state: "NOT_CHECKED",
                latestVersion: null,
                releaseUrl: null,
                checkedAt: null,
                checking: false,
              },
            })
          : route.fallback(),
      );
      await context.route("**/api/home/summary", (route) =>
        route.fulfill({
          json: {
            catalog: { total: 0, sourceModifiedAt: null },
            downloads: { total: 0, byStatus: {} },
            jobs: { byStatus: {} },
          },
        }),
      );
      await context.route("**/api/application/version", (route) =>
        route.fulfill({
          json: {
            version: "0.0.1",
            commit: "0ef2d7b",
            releasesUrl: "https://github.com/rlibanez/eplsync/releases/tag/v0.0.1",
          },
        }),
      );
      await context.route("**/api/auth/status", (route) => {
        if (route.request().method() !== "GET") return route.fallback();
        return route.fulfill({
          json: {
            initialized: true,
            registrationEnabled: false,
            approvalRequired: true,
            passwordMinimumLength: 8,
            initialAdminKeyRequired: false,
          },
        });
      });
      await page.route("**/api/catalog/import/metadata", (route) =>
        route.fulfill({ json: { metadata: null } }),
      );
      await page.route("**/api/settings/catalog", (route) =>
        route.fulfill({ json: { section: "catalog", fields: [] } }),
      );
      await page.route("**/api/catalog/covers/task", (route) =>
        route.fulfill({ json: { running: false } }),
      );
      await page.route("**/api/auth/me", (route) =>
        route.fulfill({
          json: {
            id: "test-admin",
            username: "admin",
            email: "admin@example.org",
            role: "ADMIN",
            mustChangePassword: false,
            permissions: [
              "CATALOG_READ",
              "BOOK_HISTORY_READ",
              "DOWNLOADS_DELETE",
              "TORRENT_SEND",
              "TORRENT_SYNC",
              "TORRENT_JOBS_MANAGE",
              "TORRENT_CLEANUP",
              "TORRENT_FILES_DELETE",
              "CATALOG_IMPORT",
              "CATALOG_DELETE",
              "COVERS_MANAGE",
              "EVENTS_MANAGE",
              "SETTINGS_MANAGE",
            ],
          },
        }),
      );
      let homePreferences = {
        sections: [
          { id: "header", enabled: true, bookCount: null },
          { id: "overview", enabled: true, bookCount: null },
          { id: "newReleases", enabled: true, bookCount: 10 },
          { id: "recentUpdates", enabled: true, bookCount: 10 },
          { id: "recentBooks", enabled: true, bookCount: 10 },
          {
            id: "recentEvents",
            enabled: true,
            bookCount: null,
            eventCount: 10,
          },
        ],
      };
      await page.route("**/api/auth/home", (route) => {
        if (route.request().method() === "PUT")
          homePreferences = route.request().postDataJSON();
        return route.fulfill({ json: homePreferences });
      });
      await page.route("**/api/auth/csrf", (route) =>
        route.fulfill({
          json: { token: "test-csrf", headerName: "X-CSRF-TOKEN" },
        }),
      );
      await page.route("**/api/events/unread**", (route) =>
        route.fulfill({
          json: {
            count: 0,
            cursor: Number(
              Math.max(
                route.request().postDataJSON()?.afterId ?? 0,
                ...(route.request().postDataJSON()?.readIds ?? []),
              ),
            ),
          },
        }),
      );
      await page.route("**/api/events/retention", (route) =>
        route.fulfill({ json: { maxCount: 10000, maxAgeDays: 365 } }),
      );
      await page.addInitScript(() => {
        const streams = new Set<EventTarget>();
        class MockEventSource extends EventTarget {
          onerror: (() => void) | null = null;
          constructor() {
            super();
            streams.add(this);
            setTimeout(
              () =>
                this.dispatchEvent(
                  new MessageEvent("ready", {
                    data: JSON.stringify({ cursor: 0 }),
                  }),
                ),
              0,
            );
          }
          close() {
            streams.delete(this);
          }
        }
        Object.assign(window, {
          EventSource: MockEventSource,
          __event: (name: string, data: unknown) =>
            streams.forEach((stream) =>
              stream.dispatchEvent(
                new MessageEvent(name, { data: JSON.stringify(data) }),
              ),
            ),
          __eventError: () =>
            streams.forEach((stream) =>
              (stream as MockEventSource).onerror?.(),
            ),
        });
      });
      await use();
      if (unmocked.length) {
        await testInfo.attach("unmocked-api-requests", {
          body: JSON.stringify([...new Set(unmocked)], null, 2),
          contentType: "application/json",
        });
        expect(
          unmocked,
          "Missing API mocks (see unmocked-api-requests attachment)",
        ).toEqual([]);
      }
    },
    { auto: true },
  ],
});
export async function emitEvent(page: Page, event: Record<string, unknown>) {
  await page.evaluate((data) => {
    (
      window as unknown as { __event: (name: string, data: unknown) => void }
    ).__event("event", {
      id: Date.now(),
      createdAt: new Date().toISOString(),
      category: "CATALOG",
      action: "UPDATE",
      outcome: "SUCCEEDED",
      origin: "MANUAL",
      operationId: "test-operation",
      details: {},
      ...data,
    });
  }, event);
}

// Grouped journal responses for UI tests; server grouping is verified with real SQLite tests.
export function operationResponse(
  entries: {
    id: number;
    createdAt: string;
    operationId: string;
    category: string;
    outcome: string;
    origin: string;
    action: string;
    details: Record<string, unknown>;
  }[],
  url: string,
) {
  const params = new URL(url).searchParams;
  const cursor = params.has("snapshot")
    ? Number(params.get("snapshot"))
    : Math.max(0, ...entries.map((e) => e.id));
  const groups = new Map<string, typeof entries>();
  entries
    .filter((e) => e.id <= cursor)
    .forEach((e) =>
      groups.set(e.operationId, [...(groups.get(e.operationId) || []), e]),
    );
  const items = [...groups.values()]
    .map((events) => {
      events.sort((a, b) => a.id - b.id);
      const latest = events.at(-1)!;
      const startedAt =
        events.find((e) => e.outcome === "STARTED")?.createdAt ?? null;
      const finishedAt = [
        "SUCCEEDED",
        "PARTIAL",
        "FAILED",
        "CANCELLED",
      ].includes(latest.outcome)
        ? latest.createdAt
        : null;
      return {
        latest,
        startedAt,
        finishedAt,
        firstRecordedAt: events[0].createdAt,
        durationMs:
          startedAt && finishedAt
            ? new Date(finishedAt).getTime() - new Date(startedAt).getTime()
            : null,
        events,
      };
    })
    .filter((item) =>
      (["action", "category", "outcome", "origin"] as const).every(
        (key) => !params.get(key) || params.get(key) === item.latest[key],
      ),
    )
    .sort(
      (a, b) =>
        (b.startedAt || b.firstRecordedAt).localeCompare(
          a.startedAt || a.firstRecordedAt,
        ) || b.events[0].id - a.events[0].id,
    );
  const page = Number(params.get("page") || 0);
  return {
    items: items.slice(page * 20, page * 20 + 20),
    total: items.length,
    page,
    size: 20,
    cursor,
  };
}
