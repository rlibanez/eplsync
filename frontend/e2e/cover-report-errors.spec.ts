import { test, expect } from "./fixtures";
test("cover report storage failures show a controlled explanation", async ({
  page,
}) => {
  await page.route("**/api/settings/covers", (route) =>
    route.fulfill({ json: { section: "covers", fields: [] } }),
  );
  await page.route("**/api/catalog/covers/config", (route) =>
    route.fulfill({
      json: {
        connectTimeoutMs: 3000,
        requestTimeoutMs: 3000,
        batchTimeoutMs: 4000,
        concurrency: 4,
      },
    }),
  );
  await page.route("**/api/catalog/covers/task", (route) =>
    route.fulfill({
      json: {
        task: {
          id: "failed",
          state: "FAILED",
          dryRun: false,
          onlyUnchecked: false,
          options: {
            connectTimeoutMs: 3000,
            requestTimeoutMs: 3000,
            batchTimeoutMs: 4000,
            concurrency: 4,
          },
          checked: 50,
          total: 100000,
          summary: null,
          error: "REPORT_STORAGE_FAILED",
        },
      },
    }),
  );
  await page.goto("/settings/catalog");
  await expect(
    page
      .getByRole("alert")
      .filter({ hasText: "No se pudo guardar el informe de portadas" }),
  ).toContainText("128 MiB");
});
