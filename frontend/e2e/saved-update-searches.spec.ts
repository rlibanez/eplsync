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

for (const type of ["downloads", "calibre"] as const) {
  test(`${type} clears invalidated saved rows and selection without reloading`, async ({ page }) => {
    await page.clock.install();
    let saved: Record<string, unknown> | null = {
      rows: [{
        eplId: 123, calibreId: 1, title: "Resultado invalidado", authors: "Autor",
        registeredRevision: 1, availableRevision: 2, revision: 1, catalogRevision: 2,
        status: "DOWNLOADED", comparison: "OUTDATED", presence: "PRESENT",
        downloadAvailability: "READY", coverAvailable: false,
      }],
      searchedAt: Date.now(), states: ["DOWNLOADED"], origin: "MANUAL",
    };
    await page.route(`**/api/settings/tasks/searches/${type}`, r => r.fulfill({ json: { search: saved } }));
    if (type === "downloads") {
      await page.route("**/api/settings/downloads/updates", r => r.fulfill({ json: { states: ["DOWNLOADED"] } }));
    } else {
      await page.route("**/api/settings/calibre/snapshot", r => r.fulfill({ json: { snapshot: { library: "main", syncedAt: new Date().toISOString(), total: 1, identified: 1 } } }));
    }
    await page.goto(type === "downloads" ? "/downloads/updates" : "/calibre/updates");
    await expect(page.locator("tbody")).toContainText("Resultado invalidado");
    await page.locator("tbody").getByRole("checkbox").first().check();
    await expect(page.getByRole("button", { name: /Quitar selección/ })).toBeVisible();
    saved = null;
    await page.clock.fastForward(30_000);
    await expect(page.locator("tbody")).not.toContainText("Resultado invalidado");
    await expect(page.getByRole("button", { name: /Quitar selección/ })).toHaveCount(0);
    if (type === "downloads") await expect(page.getByText(/Última búsqueda:/)).toHaveCount(0);
  });
}
