import { test, expect } from "./fixtures";
for (const permissions of [true, false]) {
  test(`cleanup priority respects permissions=${permissions}`, async ({
    page,
  }) => {
    if (!permissions)
      await page.route("**/api/auth/me", (r) =>
        r.fulfill({
          json: {
            id: "reader",
            username: "reader",
            role: "USER",
            mustChangePassword: false,
            permissions: ["TORRENT_SYNC", "TORRENT_JOBS_MANAGE"],
          },
        }),
      );
    let priorityRequested = false,
      posts = 0;
    const job = () => ({
      id: "priority",
      source: "downloads",
      action: "FILES",
      state: "RUNNING",
      result: null,
      message: null,
      library: null,
      createdAt: Date.now(),
      updatedAt: Date.now(),
      total: 17,
      selected: 17,
      ready: 0,
      excluded: 0,
      pending: 17,
      succeeded: 0,
      blocked: 0,
      failed: 0,
      uncertain: 0,
      priorityRequested,
    });
    await page.route("**/api/revision-cleanups/priority", (r) =>
      r.fulfill({ json: job() }),
    );
    await page.route("**/api/revision-cleanups/priority/items?**", (r) =>
      r.fulfill({
        json: {
          items: [],
          meta: { page: 0, size: 20, totalItems: 0, totalPages: 1 },
        },
      }),
    );
    await page.route("**/api/revision-cleanups/priority/prioritize", (r) => {
      expect(r.request().method()).toBe("POST");
      posts++;
      priorityRequested = true;
      return r.fulfill({ json: job() });
    });
    await page.goto("/downloads/jobs/cleanup/priority");
    await expect(
      page.getByRole("heading", {
        name: "Trabajo de limpieza de revisiones",
        exact: true,
      }),
    ).toBeVisible();
    const button = page.getByRole("button", {
      name: "Priorizar limpieza",
      exact: true,
    });
    if (permissions) {
      await expect(button).toBeVisible();
      await button.click();
      await expect(
        page.getByRole("button", { name: "Prioridad solicitada", exact: true }),
      ).toBeDisabled();
      expect(posts).toBe(1);
    } else {
      await expect(button).toHaveCount(0);
      expect(posts).toBe(0);
    }
  });
}
test("completed cleanup has no priority button", async ({ page }) => {
  await page.route("**/api/revision-cleanups/priority", (r) =>
    r.fulfill({
      json: {
        id: "priority",
        source: "downloads",
        action: "FILES",
        state: "COMPLETED",
        result: "SUCCEEDED",
        total: 17,
        selected: 17,
        pending: 0,
        succeeded: 17,
        blocked: 0,
        failed: 0,
        uncertain: 0,
        createdAt: Date.now(),
        updatedAt: Date.now(),
      },
    }),
  );
  await page.route("**/api/revision-cleanups/priority/items?**", (r) =>
    r.fulfill({
      json: {
        items: [],
        meta: { page: 0, size: 20, totalItems: 0, totalPages: 1 },
      },
    }),
  );
  await page.goto("/downloads/jobs/cleanup/priority");
  await expect(
    page.getByRole("heading", {
      name: "Trabajo de limpieza de revisiones",
      exact: true,
    }),
  ).toBeVisible();
  await expect(
    page.getByRole("button", { name: "Priorizar limpieza", exact: true }),
  ).toHaveCount(0);
});
