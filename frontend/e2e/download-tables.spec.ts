import { test, expect } from "./fixtures";
const meta = {
  page: 0,
  size: 20,
  totalItems: 1,
  totalPages: 1,
  hasNext: false,
  hasPrevious: false,
};
const book = {
  eplId: 23,
  title: "Un libro",
  coverUrl: "https://example.org/cover.jpg",
  coverAvailable: true,
};
for (const kind of ["state", "job"] as const) {
  test(`${kind} table shows covers, sorts on the server and persists resized columns`, async ({
    page,
  }) => {
    const sorts: string[] = [];
    await page.route("https://example.org/cover.jpg", (r) =>
      r.fulfill({
        contentType: "image/svg+xml",
        body: '<svg xmlns="http://www.w3.org/2000/svg" width="44" height="66"><rect width="44" height="66" fill="green"/></svg>',
      }),
    );
    if (kind === "state") {
      await page.route("**/api/torrent/downloads/summary?**", (r) =>
        r.fulfill({ json: { total: 1, byStatus: { SUBMITTED: 1 } } }),
      );
      await page.route("**/api/torrent/downloads?**", (r) => {
        sorts.push(new URL(r.request().url()).searchParams.get("sort")!);
        return r.fulfill({
          json: {
            items: [
              {
                ...book,
                id: "one",
                hash: "A".repeat(40),
                revision: 2,
                client: "qbittorrent",
                origin: "EPLSYNC",
                status: "SUBMITTED",
              },
            ],
            meta,
          },
        });
      });
    } else {
      await page.route("**/api/torrent/jobs/table-job", (r) =>
        r.fulfill({
          json: {
            jobId: "table-job",
            status: "COMPLETED",
            type: "UPDATE",
            client: "qbittorrent",
            previousVersions: "removeTorrent",
            cleanup: {
              waiting: 1,
              blocked: 0,
              requested: 0,
              removed: 0,
              cancelled: 0,
            },
            selectedItems: 1,
            processedItems: 1,
            selectedBooks: 1,
            accepted: 1,
            failed: 0,
            alreadyExists: 0,
            skipped: 0,
            pending: 0,
            inFlight: 0,
            cancelled: 0,
            batchSize: 10,
            concurrency: 1,
            interval: "1s",
            multipleHashes: "FIRST",
            createdAt: "2026-10-07T10:00:00Z",
            updatedAt: "2026-10-07T10:01:00Z",
          },
        }),
      );
      await page.route("**/api/torrent/jobs/table-job/items?**", (r) => {
        sorts.push(new URL(r.request().url()).searchParams.get("sort")!);
        return r.fulfill({
          json: {
            items: [
              {
                ...book,
                id: "one",
                hash: "A".repeat(40),
                status: "ACCEPTED",
                revision: 1.4,
                attempts: 1,
                message: null,
              },
            ],
            meta,
          },
        });
      });
    }
    await page.goto(
      kind === "state" ? "/downloads" : "/downloads/jobs/table-job",
    );
    const table = page.locator(".download-record-table");
    await expect(table.getByRole("link", { name: "Un libro" })).toBeVisible();
    await expect(table.locator(".book-cover img")).toHaveAttribute(
      "src",
      book.coverUrl,
    );
    await expect(table.locator("th").first()).toContainText("EPL id");
    const cells = table.locator("tbody tr").first().locator("td");
    await expect(cells.nth(2)).toHaveCSS("display", "table-cell");
    const heights = await cells.evaluateAll((elements) =>
      elements.map((cell) => cell.getBoundingClientRect().height),
    );
    expect(Math.max(...heights) - Math.min(...heights)).toBeLessThan(1);
    if (kind === "job") {
      const report = page.locator(".report-collapse");
      await expect(report).not.toHaveAttribute("open", "");
      await expect(
        report.locator("summary").getByText("Terminado", { exact: true }),
      ).toBeVisible();
      await report.locator("summary").click();
      await expect.poll(() => sorts[0]).toBe("position,asc");
      await expect(
        table.getByRole("cell", { name: "1,4", exact: true }),
      ).toBeVisible();
      const reportWidth = await page
        .locator(".job-report-groups")
        .evaluate((element) => element.getBoundingClientRect().width);
      expect(reportWidth).toBeLessThanOrEqual(584);
      await expect(page.locator(".job-report-group")).toHaveCount(4);
      const rows = page.locator(".job-report-group").nth(1).locator("dl > div");
      const positions = await rows.evaluateAll((elements) =>
        elements.map((row) => row.getBoundingClientRect()),
      );
      expect(positions.every((row) => row.x === positions[0].x)).toBe(true);
      expect(positions[1].y).toBeGreaterThan(positions[0].y);
    }
    await table.getByRole("button", { name: "Libro", exact: true }).click();
    await expect.poll(() => sorts.at(-1)).toBe("title,asc");
    await table.getByRole("button", { name: "Libro", exact: true }).click();
    await expect.poll(() => sorts.at(-1)).toBe("title,desc");
    const handle = table.getByRole("button", {
      name: "Ajustar ancho de Libro",
      exact: true,
    });
    const before = (await handle.locator("..").boundingBox())!.width;
    const box = (await handle.boundingBox())!;
    await page.mouse.move(box.x + box.width / 2, box.y + box.height / 2);
    await page.mouse.down();
    await page.mouse.move(box.x + box.width / 2 + 60, box.y + box.height / 2);
    await page.mouse.up();
    await expect
      .poll(async () => (await handle.locator("..").boundingBox())!.width)
      .toBeGreaterThan(before + 40);
    await page.reload();
    await expect
      .poll(async () => (await handle.locator("..").boundingBox())!.width)
      .toBeGreaterThan(before + 40);
    await page.screenshot({
      path: `/tmp/eplsync-${kind}-table.png`,
      fullPage: true,
    });
  });
}
