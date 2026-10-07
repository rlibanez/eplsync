import { test, expect } from "./fixtures";
import { checkTableControls } from "./table-controls";

test("jobs filter stays compact and headers request sorting before pagination", async ({
  page,
}) => {
  const requests: string[] = [];
  await page.route("**/api/torrent/jobs?**", (route) => {
    const params = new URL(route.request().url()).searchParams;
    requests.push(params.toString());
    return route.fulfill({
      json: {
        items: [
          {
            jobId: "test-job",
            status: "RUNNING",
            processedItems: 1,
            selectedItems: 2,
            selectedBooks: 2,
            accepted: 1,
            failed: 0,
            createdAt: "2026-10-06T12:00:00Z",
          },
        ],
        meta: {
          page: Number(params.get("page")),
          size: 20,
          totalItems: 40,
          totalPages: 2,
          hasNext: params.get("page") === "0",
          hasPrevious: params.get("page") === "1",
        },
      },
    });
  });
  await page.goto("/downloads/jobs");
  const filter = page.getByRole("textbox", { name: "Estado", exact: true });
  await expect(filter).toBeVisible();
  const box = (await filter.boundingBox())!;
  expect(box.width).toBeLessThanOrEqual(360);
  await expect(
    page.locator('th[data-table-column="createdAt"]'),
  ).toHaveAttribute("aria-sort", "descending");
  await page.getByRole("button", { name: "Siguiente", exact: true }).click();
  await expect
    .poll(() =>
      requests.some((query) => new URLSearchParams(query).get("page") === "1"),
    )
    .toBe(true);
  await page
    .getByRole("button", { name: "Progreso de envío", exact: true })
    .click();
  await expect
    .poll(() =>
      requests.some((query) => {
        const params = new URLSearchParams(query);
        return (
          params.get("sort") === "progress,asc" && params.get("page") === "0"
        );
      }),
    )
    .toBe(true);
  await expect(
    page.locator('th[data-table-column="progress"]'),
  ).toHaveAttribute("aria-sort", "ascending");
  await page
    .getByRole("button", { name: "Progreso de envío", exact: true })
    .click();
  await expect(
    page.locator('th[data-table-column="progress"]'),
  ).toHaveAttribute("aria-sort", "descending");
  await checkTableControls(page, "status", "Estado");
  expect(
    requests.some(
      (query) => new URLSearchParams(query).getAll("sort").length === 2,
    ),
  ).toBe(true);
  await page.setViewportSize({ width: 390, height: 844 });
  await page.waitForTimeout(400);
  expect(
    await page.evaluate(
      () => document.documentElement.scrollWidth <= innerWidth,
    ),
  ).toBe(true);
});

test("individual job has a compact filter and visible execution controls", async ({
  page,
}) => {
  let state = "RUNNING";
  const actions: string[] = [];
  await page.route("**/api/torrent/jobs/control-job**", async (route) => {
    const path = new URL(route.request().url()).pathname;
    if (path.endsWith("/items")) {
      return route.fulfill({
        json: {
          items: [],
          meta: { page: 0, size: 20, totalItems: 0, totalPages: 0 },
        },
      });
    }
    if (route.request().method() === "POST") {
      const action = path.split("/").at(-1)!;
      actions.push(action);
      state =
        action === "pause"
          ? "PAUSED"
          : action === "resume"
            ? "QUEUED"
            : "CANCELLED";
    }
    return route.fulfill({
      json: {
        jobId: "control-job",
        status: state,
        selectedBooks: 2,
        selectedItems: 2,
        processedItems: 0,
        pending: 2,
        accepted: 0,
        failed: 0,
        alreadyExists: 0,
        skipped: 0,
        inFlight: 0,
        cancelled: 0,
        batchSize: 1,
        concurrency: 1,
        interval: "1s",
        multipleHashes: "LATEST",
        createdAt: "2026-10-06T12:00:00Z",
        updatedAt: "2026-10-06T12:00:00Z",
      },
    });
  });
  await page.goto("/downloads/jobs/control-job");
  const filter = page.getByRole("textbox", {
    name: "Estado de los elementos",
    exact: true,
  });
  await expect(filter).toBeVisible();
  expect((await filter.boundingBox())!.width).toBeLessThanOrEqual(360);
  await page.getByRole("button", { name: "Pausar", exact: true }).click();
  await page.getByRole("button", { name: "Reanudar", exact: true }).click();
  const cancel = page
    .locator(".job-controls-heading")
    .getByRole("button", { name: "Cancelar trabajo", exact: true });
  await cancel.click();
  await page
    .getByRole("dialog")
    .getByRole("button", { name: "Cancelar trabajo", exact: true })
    .click();
  await expect(cancel).toHaveCount(0);
  expect(actions).toEqual(["pause", "resume", "cancel"]);
});
