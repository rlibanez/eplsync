import { test, expect } from "./fixtures";
test("download updates retain the last manual result across reload without another search", async ({
  page,
}) => {
  let searches = 0;
  await page.route("**/api/settings/downloads/updates", (r) =>
    r.fulfill({ json: { states: ["DOWNLOADED"] } }),
  );
  let saved: Record<string, unknown> | null = null;
  await page.route("**/api/settings/tasks/searches/downloads", (route) => {
    if (route.request().method() === "GET")
      return route.fulfill({ json: { search: saved } });
    searches++;
    saved = {
      rows: [
        {
          eplId: 123,
          title: "Resultado guardado",
          registeredRevision: 1,
          availableRevision: 2,
          status: "DOWNLOADED",
        },
      ],
      searchedAt: Date.now(),
      states: route.request().postDataJSON().states,
      origin: "MANUAL",
      criteria: { synchronize: true },
    };
    return route.fulfill({ json: saved });
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
