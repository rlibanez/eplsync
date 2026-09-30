import { test, expect } from "@playwright/test";
test.beforeEach(async ({ page }) => {
  await page.route("**/api/ui/config", (r) =>
    r.fulfill({ json: { defaultLanguage: "es" } }),
  );
});
test("compact sidebar keeps icon tooltips and separator only when collapsed", async ({
  page,
}) => {
  await page.goto("/settings/general");
  const sidebar = page.locator("#sidebar");
  await expect(sidebar).toHaveCSS("width", "190px");
  await expect(page.locator(".sidebar-bottom")).toHaveCSS(
    "border-top-width",
    "0px",
  );
  const toggle = page.getByRole("button", {
    name: "Plegar menú lateral",
    exact: true,
  });
  await expect(toggle).toHaveText("Plegar");
  await expect(toggle).toHaveAttribute("title", "Plegar menú lateral");
  const icons = sidebar.locator("nav a svg");
  const before = await icons.evaluateAll((nodes) =>
    nodes.map((n) => n.getBoundingClientRect().y),
  );
  await toggle.click();
  expect(
    await icons.evaluateAll((nodes) =>
      nodes.map((n) => n.getBoundingClientRect().y),
    ),
  ).toEqual(before);
  await expect(page.locator(".sidebar-bottom")).toHaveCSS(
    "border-top-width",
    "1px",
  );
  await page
    .getByRole("button", { name: "Desplegar menú lateral", exact: true })
    .click();
  await page.screenshot({
    path: "test-results/compact-sidebar.png",
    fullPage: true,
  });
});
test("back to top appears only after scrolling and is outside the table", async ({
  page,
}) => {
  await page.emulateMedia({ reducedMotion: "reduce" });
  await page.route("**/api/catalog/books?**", (r) =>
    r.fulfill({
      json: {
        items: Array.from({ length: 50 }, (_, i) => ({
          eplId: i + 1,
          title: `Libro ${i + 1}`,
          author: "Autor",
          language: "es",
          revision: 1,
          publicationYear: 2000,
          download: { items: [] },
        })),
        meta: {
          page: 0,
          size: 50,
          totalItems: 50,
          totalPages: 1,
          hasNext: false,
          hasPrevious: false,
        },
      },
    }),
  );
  await page.goto("/catalog?size=50");
  await expect(
    page.getByRole("link", { name: "Libro 50 EPL 50", exact: true }),
  ).toBeVisible();
  await expect(
    page.getByRole("button", { name: "Subir", exact: true }),
  ).toHaveCount(0);
  await page.evaluate(() => window.scrollTo(0, 900));
  const top = page.getByRole("button", { name: "Subir", exact: true });
  await expect(top).toBeVisible();
  await expect(
    page.locator(".pagination .back-to-top, table .back-to-top"),
  ).toHaveCount(0);
  await top.click();
  await expect.poll(() => page.evaluate(() => window.scrollY)).toBe(0);
  await expect(top).toHaveCount(0);
});
test("manual jobs refresh uses primary color and reports loading success and failure", async ({
  page,
}) => {
  let fail = false;
  let release: () => void = () => {};
  let block = false;
  await page.route("**/api/torrent/jobs?**", async (r) => {
    if (block)
      await new Promise<void>((resolve) => {
        release = resolve;
      });
    return fail
      ? r.fulfill({ status: 503, json: {} })
      : r.fulfill({
          json: {
            items: [],
            meta: {
              page: 0,
              size: 20,
              totalItems: 0,
              totalPages: 0,
              hasNext: false,
              hasPrevious: false,
            },
          },
        });
  });
  await page.goto("/downloads/jobs");
  await expect(
    page.getByText("No hay resultados.", { exact: true }),
  ).toBeVisible();
  const refresh = page.getByRole("button", {
    name: "Actualizar vista",
    exact: true,
  });
  await expect(refresh).toHaveAttribute("data-variant", "filled");
  block = true;
  await refresh.click();
  await expect(
    page.getByRole("button", { name: "Actualizando…", exact: true }),
  ).toBeDisabled();
  block = false;
  release();
  await expect(
    page
      .getByRole("status")
      .filter({ hasText: "Vista actualizada correctamente." }),
  ).toBeVisible();
  fail = true;
  await refresh.click();
  await expect(page.locator(".refresh-feedback")).toContainText(
    "No se pudo actualizar la vista",
  );
  await expect(page.locator(".refresh-feedback")).not.toContainText(
    "correctamente",
  );
});
