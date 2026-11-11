import { test, expect, operationResponse } from "./fixtures";

for (const access of [true, false]) {
  test(`Calibre event links its original job only with job access (${access})`, async ({
    page,
  }) => {
    await page.route("**/api/auth/me", (r) =>
      r.fulfill({
        json: {
          id: "reader",
          username: "Reader",
          role: "USER",
          mustChangePassword: false,
          permissions: [
            "EVENTS_MANAGE",
            ...(access ? ["CALIBRE_JOBS_MANAGE"] : []),
          ],
        },
      }),
    );
    await page.route("**/api/catalog/covers/task", (r) =>
      r.fulfill({ json: { task: null } }),
    );
    const id = "f9589ff3-d188-4c66-99f3-38c1cb142992";
    const event = {
      id: 1,
      category: "JOB",
      action: "CALIBRE_SEND",
      outcome: "PARTIAL",
      origin: "MANUAL",
      operationId: id,
      createdAt: "2026-11-03T16:44:07Z",
      details: {
        accepted: 14,
        total: 16,
        failed: 2,
        omittedItems: 0,
        library: "library",
        uncertain: 0,
      },
    };
    await page.route("**/api/events/operations?**", (r) =>
      r.fulfill({ json: operationResponse([event], r.request().url()) }),
    );
    await page.goto("/events");
    await page.getByText("Ver resumen", { exact: true }).click();
    await expect(page.locator(".event-details")).toContainText(
      "Omitidos por el usuario",
    );
    await expect(page.locator(".event-details")).toContainText("Biblioteca");
    await expect(page.locator(".event-details")).toContainText("Sin confirmar");
    const link = page.getByRole("link", { name: id, exact: true });
    if (access)
      await expect(link).toHaveAttribute("href", `/calibre/jobs/${id}`);
    else {
      await expect(link).toHaveCount(0);
      await expect(
        page.getByText(`Ejecución: ${id}`, { exact: true }),
      ).toBeVisible();
    }
  });
}

test("event summaries link the affected job and keep unrelated execution IDs as text", async ({
  page,
}) => {
  await page.route("**/api/auth/me", (route) =>
    route.fulfill({
      json: {
        id: "reader",
        username: "Reader",
        role: "USER",
        mustChangePassword: false,
        permissions: [
          "EVENTS_MANAGE",
          "TORRENT_JOBS_MANAGE",
          "TORRENT_SYNC",
          "CALIBRE_READ",
        ],
      },
    }),
  );
  await page.route("**/api/catalog/covers/task", (route) =>
    route.fulfill({ json: { task: null } }),
  );
  const examples = [
    {
      category: "JOB",
      action: "DOWNLOAD",
      operationId: "download-job",
      details: {},
    },
    {
      category: "JOB",
      action: "REVISION_CLEANUP",
      operationId: "download-cleanup",
      details: { source: "downloads" },
    },
    {
      category: "JOB",
      action: "REVISION_CLEANUP_RETRY",
      operationId: "retry-run",
      details: { source: "calibre", jobId: "calibre-cleanup" },
    },
    {
      category: "TORRENT",
      action: "CLEANUP",
      operationId: "legacy-run",
      details: { jobId: "download-parent" },
    },
    {
      category: "CATALOG",
      action: "IMPORT",
      operationId: "import-run",
      details: {},
    },
  ];
  const events = examples.map((example, index) => ({
    ...example,
    id: index + 1,
    outcome: "SUCCEEDED",
    origin: "MANUAL",
    createdAt: "2026-11-03T16:44:07Z",
  }));
  await page.route("**/api/events/operations?**", (route) =>
    route.fulfill({ json: operationResponse(events, route.request().url()) }),
  );
  await page.goto("/events");
  const summaries = page.getByText("Ver resumen", { exact: true });
  await expect(summaries).toHaveCount(examples.length);
  for (const summary of await summaries.all()) await summary.click();

  for (const [id, path] of [
    ["download-job", "/downloads/jobs/download-job"],
    ["download-cleanup", "/cleanup/jobs/download-cleanup"],
    ["calibre-cleanup", "/cleanup/jobs/calibre-cleanup"],
    ["download-parent", "/downloads/jobs/download-parent"],
  ]) {
    await expect(
      page.getByRole("link", { name: id, exact: true }),
    ).toHaveAttribute("href", path);
  }
  for (const id of ["retry-run", "legacy-run", "import-run"]) {
    await expect(
      page.getByText(`Ejecución: ${id}`, { exact: true }),
    ).toBeVisible();
    await expect(page.getByRole("link", { name: id, exact: true })).toHaveCount(
      0,
    );
  }
});
