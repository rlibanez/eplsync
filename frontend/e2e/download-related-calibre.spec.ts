import { test, expect, type Page } from "./fixtures";

const counts = {
  total: 5,
  sent: 1,
  existing: 1,
  pending: 1,
  failed: 1,
  uncertain: 1,
  cancelled: 0,
};
const related = {
  counts,
  jobs: [
    {
      id: "calibre-main",
      library: "Principal",
      state: "RUNNING",
      counts: { ...counts, total: 3, failed: 0, uncertain: 0 },
    },
    {
      id: "calibre-other",
      library: "Secundaria",
      state: "PAUSED",
      counts: { ...counts, total: 2, sent: 0, existing: 0, pending: 0 },
    },
  ],
};
const job = {
  jobId: "download",
  type: "DOWNLOAD",
  status: "DOWNLOAD_WAIT",
  client: "qbittorrent",
  selectedBooks: 5,
  selectedItems: 5,
  processedItems: 5,
  accepted: 5,
  failed: 0,
  skipped: 0,
  alreadyExists: 0,
  pending: 0,
  inFlight: 0,
  cancelled: 0,
  batchSize: 100,
  concurrency: 5,
  interval: "0ms",
  calibre: related,
};
const meta = {
  page: 0,
  size: 20,
  totalItems: 0,
  totalPages: 0,
  hasNext: false,
  hasPrevious: false,
};
async function setup(page: Page, permissions?: string[]) {
  if (permissions)
    await page.route("**/api/auth/me", (route) =>
      route.fulfill({
        json: {
          id: "reader",
          username: "reader",
          role: "USER",
          mustChangePassword: false,
          permissions: ["TORRENT_JOBS_MANAGE", ...permissions],
        },
      }),
    );
  await page.route("**/api/torrent/jobs?**", (route) => {
    const p = Number(
      new URL(route.request().url()).searchParams.get("page") ?? 0,
    );
    return route.fulfill({
      json: {
        items:
          p === 0
            ? [
                job,
                { ...job, jobId: "cleanup", type: "CLEANUP", calibre: null },
                { ...job, jobId: "none", calibre: null },
              ]
            : [{ ...job, jobId: "second-page" }],
        meta: {
          ...meta,
          page: p,
          totalItems: 40,
          totalPages: 2,
          hasNext: p === 0,
          hasPrevious: p > 0,
        },
      },
    });
  });
  await page.route("**/api/torrent/jobs/download**", (route) =>
    route.fulfill({
      json: new URL(route.request().url()).pathname.endsWith("/items")
        ? { items: [], meta }
        : job,
    }),
  );
}

test("optional Calibre column shows linked progress, breakdown and no count for cleanup", async ({
  page,
}) => {
  await setup(page);
  await page.goto("/downloads/jobs");
  const row = page
    .locator("tbody tr")
    .filter({ has: page.getByRole("link", { name: "download", exact: true }) });
  const progress = row.getByText("2/5 completados", { exact: true });
  await expect(progress).toBeVisible();
  await progress.focus();
  await expect(page.getByRole("tooltip")).toHaveText(
    "1 enviados · 1 ya existentes · 1 pendientes · 1 con errores · 1 sin confirmar · 0 cancelados",
  );
  const index = await page
    .locator("th[data-table-column]")
    .evaluateAll((elements) =>
      elements.findIndex(
        (el) => el.getAttribute("data-table-column") === "calibre",
      ),
    );
  for (const id of ["cleanup", "none"])
    await expect(
      page
        .locator("tbody tr")
        .filter({ has: page.getByRole("link", { name: id, exact: true }) })
        .locator("td")
        .nth(index + 1),
    ).toHaveText("—");
  await page.getByRole("button", { name: "Columnas", exact: true }).click();
  const picker = page.locator(".column-picker");
  await picker
    .getByRole("checkbox", { name: "Calibre", exact: true })
    .uncheck();
  await expect(page.locator('th[data-table-column="calibre"]')).toHaveCount(0);
  await picker.getByRole("checkbox", { name: "Calibre", exact: true }).check();
  await page.keyboard.press("Escape");
  await page.getByRole("button", { name: "Siguiente", exact: true }).click();
  await expect(
    page.getByText("2/5 completados", { exact: true }),
  ).toBeVisible();
});

for (const width of [1440, 390])
  test(`related jobs report preserves links, library, state and scoped counts at ${width}px`, async ({
    page,
  }) => {
    await page.setViewportSize({ width, height: 1000 });
    await setup(page);
    await page.goto("/downloads/jobs/download");
    await page.getByText("Informe del trabajo", { exact: true }).click();
    const section = page
      .locator(".job-report-group")
      .filter({
        has: page.getByRole("heading", {
          name: "Trabajos de Calibre relacionados",
          exact: true,
        }),
      });
    await expect(
      section.getByRole("link", { name: "calibre-main", exact: true }),
    ).toHaveAttribute("href", "/calibre/jobs/calibre-main");
    await expect(
      section.getByRole("link", { name: "calibre-other", exact: true }),
    ).toHaveAttribute("href", "/calibre/jobs/calibre-other");
    await expect(section).toContainText("Principal");
    await expect(section).toContainText("Secundaria");
    await expect(section).toContainText("En ejecución");
    await expect(section).toContainText("Pausado");
    await expect(
      section.getByText("2/3 completados", { exact: true }),
    ).toBeVisible();
    await expect(
      section.getByText("0/2 completados", { exact: true }),
    ).toBeVisible();
    expect(
      await page.evaluate(
        () => document.documentElement.scrollWidth <= window.innerWidth,
      ),
    ).toBe(true);
  });

for (const permissions of [
  [],
  ["CALIBRE_READ"],
  ["CALIBRE_JOBS_MANAGE"],
  ["CALIBRE_SEND"],
])
  test(`related Calibre visibility with ${permissions.join(",") || "no Calibre permission"}`, async ({
    page,
  }) => {
    await setup(page, permissions);
    const allowed =
      permissions.includes("CALIBRE_SEND") ||
      permissions.includes("CALIBRE_JOBS_MANAGE");
    await page.goto("/downloads/jobs");
    await expect(page.locator('th[data-table-column="calibre"]')).toHaveCount(
      allowed ? 1 : 0,
    );
    await page.goto("/downloads/jobs/download");
    await page.getByText("Informe del trabajo", { exact: true }).click();
    await expect(
      page.getByRole("heading", {
        name: "Trabajos de Calibre relacionados",
        exact: true,
      }),
    ).toHaveCount(allowed ? 1 : 0);
    await expect(
      page.getByRole("link", { name: "calibre-main", exact: true }),
    ).toHaveCount(allowed ? 1 : 0);
  });

test("unlinked job explains the absence of related Calibre jobs", async ({
  page,
}) => {
  await setup(page);
  await page.route("**/api/torrent/jobs/download", (route) =>
    route.fulfill({ json: { ...job, calibre: null } }),
  );
  await page.goto("/downloads/jobs/download");
  await page.getByText("Informe del trabajo", { exact: true }).click();
  await expect(
    page.getByText("Sin trabajos de Calibre relacionados.", { exact: true }),
  ).toBeVisible();
});
