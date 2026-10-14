import { test, expect } from "./fixtures";
test("download updates retain the last manual result across reload without another search", async ({
  page,
}) => {
  let searches = 0;
  await page.route("**/api/settings/downloads/updates", (r) =>
    r.fulfill({ json: { states: ["DOWNLOADED"] } }),
  );
  await page.route("**/api/torrent/revision-updates/search?**", (r) => {
    searches++;
    return r.fulfill({
      json: {
        items: [
          {
            eplId: 123,
            title: "Resultado guardado",
            registeredRevision: 1,
            availableRevision: 2,
            status: "DOWNLOADED",
          },
        ],
        meta: {
          page: 0,
          size: 1000,
          totalItems: 1,
          totalPages: 1,
          hasNext: false,
          hasPrevious: false,
        },
      },
    });
  });
  await page.goto("/downloads/updates");
  await expect(page.locator("tbody")).toContainText(
    "No hay actualizaciones pendientes.",
  );
  expect(searches).toBe(0);
  await page
    .getByRole("button", { name: "Buscar actualizaciones", exact: true })
    .click();
  await page
    .getByRole("dialog")
    .getByRole("button", { name: "Buscar", exact: true })
    .click();
  await expect(page.locator("tbody")).toContainText("Resultado guardado");
  await page.reload();
  await expect(page.locator("tbody")).toContainText("Resultado guardado");
  await expect(page.getByText(/Última búsqueda:/)).toBeVisible();
  expect(searches).toBe(1);
});
