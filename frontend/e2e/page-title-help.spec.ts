import { test, expect } from "./fixtures";

test("page descriptions are available by hover, keyboard and touch without a subtitle", async ({
  page,
}) => {
  await page.route("**/api/torrent/jobs?**", (route) =>
    route.fulfill({
      json: {
        items: [],
        meta: { page: 0, size: 20, totalItems: 0, totalPages: 0 },
      },
    }),
  );
  await page.goto("/downloads/jobs");
  const info = page.getByRole("heading", {
    name: "Trabajos de descargas",
    exact: true,
  });
  const description = "Controlan los envíos de EPL Sync.";
  await expect(
    page.getByRole("heading", { name: "Trabajos de descargas", exact: true }),
  ).toBeVisible();
  await expect(page.getByText(description, { exact: false })).not.toBeVisible();
  await info.hover();
  await expect(page.getByRole("tooltip")).toContainText(description);
  await page
    .getByRole("button", { name: "Actualizar vista", exact: true })
    .hover();
  await expect(page.getByRole("tooltip")).not.toBeVisible();
  await info.focus();
  await expect(page.getByRole("tooltip")).toContainText(description);
  await info.press("Escape");
  await expect(page.getByRole("tooltip")).not.toBeVisible();
  await page.setViewportSize({ width: 390, height: 844 });
  await info.click();
  await expect(page.getByRole("tooltip")).toContainText(description);
  expect(
    await page.evaluate(
      () => document.documentElement.scrollWidth <= innerWidth,
    ),
  ).toBe(true);
});

test("catalog date uses the CSV wall time and disappears when no catalog is loaded", async ({
  page,
}) => {
  let loaded = true;
  await page.route("**/api/catalog/import/metadata", (route) =>
    route.fulfill({
      json: {
        metadata: loaded ? { sourceModifiedAt: "2026-10-15T09:15:00" } : null,
      },
    }),
  );
  await page.route("**/api/catalog/books?**", (route) =>
    route.fulfill({
      json: {
        items: [],
        meta: {
          page: 0,
          size: 20,
          totalItems: 0,
          totalPages: 0,
          first: true,
          last: true,
          hasNext: false,
          hasPrevious: false,
        },
      },
    }),
  );
  await page.goto("/catalog");
  await expect(page.getByText(/Fecha del catálogo:/)).toHaveText(
    /Fecha del catálogo:.*15 oct 2026.*9:15/,
  );
  loaded = false;
  await page.reload();
  await expect(
    page.getByRole("heading", { name: "Catálogo", exact: true }),
  ).toBeVisible();
  await expect(page.getByText(/Fecha del catálogo:/)).toHaveCount(0);
});
