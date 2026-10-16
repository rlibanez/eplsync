import { fulfillSavedSearch } from "./saved-search-response";
import { test, expect } from "./fixtures";
test("download updates retain the last manual result across reload without another search", async ({
  page,
}) => {
  let searches = 0;
  await page.route("**/api/settings/downloads/updates", (r) =>
    fulfillSavedSearch(r, { json: { states: ["DOWNLOADED"] } }),
  );
  let saved: Record<string, unknown> | null = null;
  await page.route("**/api/settings/tasks/searches/downloads**", (route) => {
    if (route.request().method() === "GET")
      return fulfillSavedSearch(route, { json: { search: saved } });
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
    return fulfillSavedSearch(route, { json: saved });
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
  test(`${type} clears invalidated saved rows and selection without reloading`, async ({
    page,
  }) => {
    await page.clock.install();
    let saved: Record<string, unknown> | null = {
      rows: [
        {
          eplId: 123,
          calibreId: 1,
          title: "Resultado invalidado",
          authors: "Autor",
          registeredRevision: 1,
          availableRevision: 2,
          revision: 1,
          catalogRevision: 2,
          status: "DOWNLOADED",
          comparison: "OUTDATED",
          presence: "PRESENT",
          downloadAvailability: "READY",
          coverAvailable: false,
        },
      ],
      searchedAt: Date.now(),
      states: ["DOWNLOADED"],
      origin: "MANUAL",
    };
    await page.route(`**/api/settings/tasks/searches/${type}**`, (r) =>
      fulfillSavedSearch(r, { json: { search: saved } }),
    );
    if (type === "downloads") {
      await page.route("**/api/settings/downloads/updates", (r) =>
        fulfillSavedSearch(r, { json: { states: ["DOWNLOADED"] } }),
      );
    } else {
      await page.route("**/api/settings/calibre/snapshot", (r) =>
        fulfillSavedSearch(r, {
          json: {
            snapshot: {
              library: "main",
              syncedAt: new Date().toISOString(),
              total: 1,
              identified: 1,
            },
          },
        }),
      );
    }
    await page.goto(
      type === "downloads" ? "/downloads/updates" : "/calibre/updates",
    );
    await expect(page.locator("tbody")).toContainText("Resultado invalidado");
    await page.locator("tbody").getByRole("checkbox").first().check();
    await expect(
      page.getByRole("button", { name: /Quitar selección/ }),
    ).toBeVisible();
    saved = null;
    await page.clock.fastForward(30_000);
    await expect(page.locator("tbody")).not.toContainText(
      "Resultado invalidado",
    );
    await expect(
      page.getByRole("button", { name: /Quitar selección/ }),
    ).toHaveCount(0);
    if (type === "downloads")
      await expect(page.getByText(/Última búsqueda:/)).toHaveCount(0);
  });
}

for (const type of ["downloads", "calibre"] as const) {
  test(`${type} selects a large saved result by pages and polls metadata only`, async ({
    page,
  }) => {
    await page.clock.install();
    const saved = {
      searchedAt: Date.now(),
      states: ["DOWNLOADED"],
      origin: "SCHEDULED",
      rows: Array.from({ length: 1205 }, (_, index) => ({
        eplId: index + 1,
        calibreId: index + 1,
        title: `Libro ${index + 1}`,
        authors: "Autor",
        registeredRevision: 1,
        availableRevision: 2,
        revision: 1,
        catalogRevision: 2,
        status: "DOWNLOADED",
        comparison: "OUTDATED",
        presence: "PRESENT",
        downloadAvailability: "READY",
      })),
    };
    let metadataRequests = 0;
    const rowRequests: URLSearchParams[] = [];
    await page.route(`**/api/settings/tasks/searches/${type}**`, (route) => {
      const url = new URL(route.request().url());
      if (url.pathname.endsWith("/rows")) rowRequests.push(url.searchParams);
      else metadataRequests++;
      return fulfillSavedSearch(route, { json: { search: saved } });
    });
    await page.route("**/api/settings/downloads/updates", (route) =>
      route.fulfill({ json: { states: ["DOWNLOADED"] } }),
    );
    await page.route("**/api/settings/calibre/snapshot", (route) =>
      route.fulfill({ json: { snapshot: null } }),
    );
    await page.goto(
      type === "downloads" ? "/downloads/updates" : "/calibre/updates",
    );
    await expect(page.locator("tbody tr")).toHaveCount(20);
    await page
      .getByRole("button", { name: "Seleccionar todo (1205)", exact: true })
      .click();
    await expect(
      page.getByRole("button", {
        name: "Quitar selección (1205)",
        exact: true,
      }),
    ).toBeVisible();
    expect(
      rowRequests
        .filter((p) => p.get("size") === "1000")
        .map((p) => p.get("page")),
    ).toEqual(["0", "1"]);
    expect(
      rowRequests.every(
        (p) => p.get("generation") === String(saved.searchedAt),
      ),
    ).toBe(true);
    const before = rowRequests.length,
      polls = metadataRequests;
    await page.clock.fastForward(30_000);
    await expect.poll(() => metadataRequests).toBeGreaterThan(polls);
    expect(rowRequests.length).toBe(before);
    await page
      .getByRole("button", { name: "Quitar selección (1205)", exact: true })
      .click();
    await expect(
      page.getByRole("button", { name: /Quitar selección/ }),
    ).toHaveCount(0);
  });
}

for (const type of ["downloads", "calibre"] as const) {
  test(`${type} switches generations without requesting obsolete rows and recovers external replacements`, async ({
    page,
  }) => {
    let generation = 100;
    const stale: number[] = [];
    const metadata = () => ({
      searchedAt: generation,
      states: ["DOWNLOADED"],
      origin: "MANUAL",
    });
    await page.route("**/api/settings/downloads/updates", (r) =>
      r.fulfill({ json: { states: ["DOWNLOADED"] } }),
    );
    await page.route("**/api/settings/calibre/snapshot", (r) =>
      r.fulfill({ json: { snapshot: null } }),
    );
    await page.route(`**/api/settings/tasks/searches/${type}**`, (r) => {
      const url = new URL(r.request().url());
      if (url.pathname.endsWith("/rows")) {
        const requested = Number(url.searchParams.get("generation"));
        if (requested !== generation) {
          stale.push(requested);
          return r.fulfill({
            status: 400,
            json: {
              details: "La búsqueda guardada ha cambiado; actualiza la vista",
            },
          });
        }
        return r.fulfill({
          json: {
            items: [
              {
                eplId: 123,
                calibreId: 1,
                title: `Resultado ${generation}`,
                authors: "Autor",
                registeredRevision: 1,
                availableRevision: 2,
                revision: 1,
                catalogRevision: 2,
                status: "DOWNLOADED",
                comparison: "OUTDATED",
                presence: "PRESENT",
                downloadAvailability: "READY",
                coverAvailable: false,
              },
            ],
            meta: {
              page: 0,
              size: 20,
              totalItems: 1,
              totalPages: 1,
              hasNext: false,
              hasPrevious: false,
            },
          },
        });
      }
      if (r.request().method() === "POST") {
        generation += 100;
        return r.fulfill({
          json:
            type === "downloads"
              ? metadata()
              : { search: metadata(), snapshot: null },
        });
      }
      return r.fulfill({ json: { search: metadata() } });
    });
    await page.goto(
      type === "downloads" ? "/downloads/updates" : "/calibre/updates",
    );
    await expect(page.locator("tbody")).toContainText("Resultado 100");
    for (const next of [200, 300]) {
      await page.locator("tbody").getByRole("checkbox").first().check();
      await page
        .getByRole("button", { name: "Buscar actualizaciones", exact: true })
        .click();
      if (type === "downloads")
        await page
          .getByRole("dialog")
          .getByRole("button", { name: "Buscar", exact: true })
          .click();
      await expect(page.locator("tbody")).toContainText(`Resultado ${next}`);
      await expect(
        page.getByRole("button", { name: /Quitar selección/ }),
      ).toHaveCount(0);
    }
    expect(stale).toEqual([]);
    generation = 400;
    await page
      .locator("thead")
      .getByRole("button", { name: "Libro", exact: true })
      .click();
    await expect(page.locator("tbody")).toContainText("Resultado 400");
    expect(stale).toEqual([300]);
  });
}

for (const type of ["downloads", "calibre"] as const) {
  test(`${type} ignores an old metadata response arriving after a manual search`, async ({
    page,
  }) => {
    let release!: () => void;
    const held = new Promise<void>((resolve) => {
      release = resolve;
    });
    let initialRead = false;
    let searched = false;
    const search = { searchedAt: 200, states: ["DOWNLOADED"] };
    await page.route("**/api/settings/downloads/updates", (r) =>
      r.fulfill({ json: { states: ["DOWNLOADED"] } }),
    );
    await page.route("**/api/settings/calibre/snapshot", (r) =>
      r.fulfill({ json: { snapshot: null } }),
    );
    await page.route(`**/api/settings/tasks/searches/${type}**`, async (r) => {
      if (new URL(r.request().url()).pathname.endsWith("/rows"))
        return fulfillSavedSearch(r, {
          json: {
            search: {
              ...search,
              rows: [
                {
                  eplId: 123,
                  calibreId: 1,
                  title: "Resultado nuevo",
                  registeredRevision: 1,
                  availableRevision: 2,
                  revision: 1,
                  catalogRevision: 2,
                  status: "DOWNLOADED",
                  downloadAvailability: "READY",
                },
              ],
            },
          },
        });
      if (r.request().method() === "POST") {
        searched = true;
        return r.fulfill({
          json: type === "downloads" ? search : { search, snapshot: null },
        });
      }
      if (!initialRead) {
        initialRead = true;
        await held;
        await r.fulfill({ json: { search: null } }).catch(() => {});
        return;
      }
      return r.fulfill({ json: { search: searched ? search : null } });
    });
    await page.goto(
      type === "downloads" ? "/downloads/updates" : "/calibre/updates",
    );
    await expect.poll(() => initialRead).toBe(true);
    await page
      .getByRole("button", { name: "Buscar actualizaciones", exact: true })
      .click();
    if (type === "downloads")
      await page
        .getByRole("dialog")
        .getByRole("button", { name: "Buscar", exact: true })
        .click();
    await expect(page.locator("tbody")).toContainText("Resultado nuevo");
    release();
    await page
      .getByRole("button", { name: "Buscar actualizaciones", exact: true })
      .focus();
    await expect(page.locator("tbody")).toContainText("Resultado nuevo");
  });
}
