import { test, expect, operationResponse } from "./fixtures";

test("read marks survive account switches without being shared", async ({
  page,
}) => {
  let account = "alice";
  const requests: { account: string; afterId: number; readIds: number[] }[] =
    [];
  const entries = [1, 2].map((id) => ({
    id,
    createdAt: "2026-10-08T10:00:00Z",
    operationId: `operation-${id}`,
    category: "CATALOG",
    outcome: "SUCCEEDED",
    origin: "MANUAL",
    action: "UPDATE",
    details: {},
  }));
  await page.route("**/api/auth/me", (route) =>
    route.fulfill({
      json: {
        id: account,
        username: account,
        email: `${account}@example.org`,
        role: "USER",
        mustChangePassword: false,
        permissions: ["EVENTS_MANAGE"],
      },
    }),
  );
  await page.route("**/api/events/unread**", (route) => {
    const { afterId, readIds } = route.request().postDataJSON();
    requests.push({ account, afterId, readIds });
    return route.fulfill({
      json: {
        cursor: 2,
        count: entries.filter((e) => e.id > afterId && !readIds.includes(e.id))
          .length,
      },
    });
  });
  await page.route("**/api/events/operations?**", (route) => {
    const id = new URL(route.request().url()).searchParams.get("operationId");
    return route.fulfill({
      json: operationResponse(
        id ? entries.filter((e) => e.operationId === id) : entries,
        route.request().url(),
      ),
    });
  });
  await page.goto("/events?operationId=operation-1");
  await expect
    .poll(() =>
      page.evaluate(() =>
        localStorage.getItem("eplsync.events.alice.readItems"),
      ),
    )
    .toBe("[1]");
  account = "bob";
  await page.goto("/settings/account");
  await expect
    .poll(() => requests.filter((r) => r.account === "bob").at(-1))
    .toEqual({ account: "bob", afterId: 0, readIds: [] });
  await page.goto("/events?operationId=operation-2");
  await expect
    .poll(() =>
      page.evaluate(() => localStorage.getItem("eplsync.events.bob.readItems")),
    )
    .toBe("[2]");
  account = "alice";
  await page.goto("/settings/account");
  await expect
    .poll(() => requests.filter((r) => r.account === "alice").at(-1))
    .toEqual({ account: "alice", afterId: 0, readIds: [1] });
  await page.goto("/events");
  await expect
    .poll(() =>
      page.evaluate(() =>
        localStorage.getItem("eplsync.events.alice.lastRead"),
      ),
    )
    .toBe("2");
  account = "bob";
  await page.goto("/settings/account");
  await expect
    .poll(() => requests.filter((r) => r.account === "bob").at(-1))
    .toEqual({ account: "bob", afterId: 0, readIds: [2] });
});
