import { test, expect } from "./fixtures";
const meta = {
  page: 0,
  size: 20,
  totalItems: 0,
  totalPages: 1,
  hasNext: false,
  hasPrevious: false,
};
const ready = {
  id: "item-1",
  eplId: 123,
  title: "Libro de prueba",
  revision: "1.1",
  oldId: "old-record",
  replacementId: "new-record",
  replacementRevision: "1.2",
  formats: ["EPUB", "PDF"],
  state: "READY",
  message: null,
  dispatched: false,
};
const excluded = {
  ...ready,
  id: "item-2",
  eplId: 456,
  title: "Libro protegido",
  state: "EXCLUDED",
  message: "Los archivos están compartidos con otro torrent",
};
const job = {
  id: "cleanup-job",
  source: "downloads",
  action: "RECORD",
  state: "PREVIEW",
  result: null as string | null,
  message: null,
  library: null,
  createdAt: 1792140000000,
  total: 2,
  selected: 0,
  ready: 1,
  excluded: 1,
  pending: 0,
  succeeded: 0,
  blocked: 0,
  failed: 0,
  uncertain: 0,
};
for (const source of ["downloads", "calibre"] as const) {
  test(`${source}: preview, selection, confirmation and tracking`, async ({
    page,
  }) => {
    await page.route("**/api/torrent/downloads?**", (r) =>
      r.fulfill({ json: { items: [], meta } }),
    );
    await page.route("**/api/calibre/books?**", (r) =>
      r.fulfill({ json: { items: [], meta } }),
    );
    await page.route("**/api/settings/calibre/snapshot", (r) =>
      r.fulfill({ json: { snapshot: null } }),
    );
    let current = {
      ...job,
      source,
      action: source === "calibre" ? "BOOK" : "FILES",
    };
    let creates = 0;
    await page.route("**/api/revision-cleanups/preview", async (r) => {
      expect(r.request().postDataJSON()).toEqual({
        source,
        action: current.action,
        allowUndownloaded: source === "downloads",
        ids: [],
      });
      await r.fulfill({ json: current });
    });
    await page.route("**/api/revision-cleanups/cleanup-job/items?**", (r) =>
      r.fulfill({
        json: { items: [ready, excluded], meta: { ...meta, totalItems: 2 } },
      }),
    );
    await page.route("**/api/revision-cleanups/cleanup-job", (r) =>
      r.fulfill({ json: current }),
    );
    await page.route(
      "**/api/revision-cleanups/cleanup-job/start",
      async (r) => {
        creates++;
        expect(r.request().postDataJSON()).toEqual({
          ids: [ready.id],
          confirm: true,
          confirmDestructive: true,
        });
        current = {
          ...current,
          state: "COMPLETED",
          result: "SUCCEEDED",
          selected: 1,
          succeeded: 1,
        };
        await r.fulfill({ json: current });
      },
    );
    await page.goto(source === "calibre" ? "/calibre" : "/downloads");
    await page
      .getByRole("button", { name: "Limpiar revisiones antiguas", exact: true })
      .click();
    const modal = page.getByRole("dialog");
    if (source === "downloads") {
      const force = modal.getByRole("checkbox", {
        name: "Permitir la limpieza aunque la revisión superior no esté descargada",
      });
      await expect(force).not.toBeChecked();
      await force.check();
      await expect(modal).not.toContainText(
        "Puedes quedarte sin una copia descargada",
      );
      await modal.getByRole("textbox", { name: "Acción", exact: true }).click();
      await page
        .getByRole("option", {
          name: "Borrar torrent, archivos y registro de EPL Sync",
          exact: true,
        })
        .click();
    } else {
      await expect(
        modal.getByRole("textbox", { name: "Acción", exact: true }),
      ).toHaveCount(0);
      await expect(modal).toContainText(
        "Borrar la entrada antigua completa de Calibre y todos sus formatos",
      );
      await expect(modal).not.toContainText(
        "Borrar el EPUB de la revisión antigua",
      );
    }
    await modal
      .getByRole("button", { name: "Previsualizar", exact: true })
      .click();
    await expect(
      modal.getByRole("checkbox", { name: "Seleccionar Libro de prueba" }),
    ).toBeChecked();
    await expect(
      modal.getByRole("checkbox", { name: "Seleccionar Libro protegido" }),
    ).toBeDisabled();
    await expect(modal).toContainText("Los archivos están compartidos");
    if (source === "downloads")
      await page.screenshot({ path: "/tmp/revision-cleanup-preview.png" });
    await modal.getByRole("button", { name: "Quitar selección (1)" }).click();
    await expect(
      modal.getByRole("button", { name: "Siguiente", exact: true }),
    ).toBeDisabled();
    await modal
      .getByRole("checkbox", { name: "Seleccionar Libro de prueba" })
      .check();
    await modal.getByRole("button", { name: "Siguiente", exact: true }).click();
    await expect(
      modal.getByRole("button", { name: "Crear trabajo de limpieza" }),
    ).toBeDisabled();
    await modal
      .getByRole("checkbox", { name: "He revisado la selección" })
      .check();
    await expect(
      modal.getByRole("button", { name: "Crear trabajo de limpieza" }),
    ).toBeDisabled();
    await modal
      .getByRole("checkbox", { name: "Confirmo la eliminación irreversible" })
      .check();
    await modal
      .getByRole("button", { name: "Crear trabajo de limpieza" })
      .click();
    await expect(page).toHaveURL(
      new RegExp(`/${source}/jobs/cleanup/cleanup-job$`),
    );
    await expect(
      page.getByRole("heading", { name: "Trabajo de limpieza de revisiones" }),
    ).toBeVisible();
    expect(creates).toBe(1);
    await page.reload();
    await expect(
      page.getByRole("heading", { name: "Trabajo de limpieza de revisiones" }),
    ).toBeVisible();
  });
}
test("unconfirmed work offers verification only", async ({ page }) => {
  await page.route("**/api/revision-cleanups/cleanup-job", (r) =>
    r.fulfill({
      json: { ...job, state: "COMPLETED", result: "FAILED", uncertain: 1 },
    }),
  );
  await page.route("**/api/revision-cleanups/cleanup-job/items?**", (r) =>
    r.fulfill({ json: { items: [{ ...ready, state: "UNCERTAIN" }], meta } }),
  );
  let checks = 0;
  await page.route("**/api/revision-cleanups/cleanup-job/verify", (r) => {
    checks++;
    return r.fulfill({ json: job });
  });
  await page.goto("/cleanup/jobs/cleanup-job");
  await expect(
    page.getByText("Comprobar solo consulta", { exact: false }),
  ).toBeVisible();
  await expect(
    page.getByRole("button", { name: "Crear trabajo de limpieza" }),
  ).toHaveCount(0);
  await page
    .getByRole("button", { name: "Comprobar borrados sin confirmar" })
    .click();
  await expect.poll(() => checks).toBe(1);
});

for (const source of ["downloads", "calibre"] as const) {
  test(`${source}: cleanup shares the main job table and type filter`, async ({
    page,
  }) => {
    const row = {
      ...job,
      jobId: job.id,
      id: job.id,
      type: "CLEANUP",
      state: "COMPLETED",
      status: "COMPLETED",
      createdAt: "2026-10-16T10:00:00Z",
      updatedAt: "2026-10-16T10:00:00Z",
      username: "admin",
      library: "main",
      selectedBooks: 1,
      selectedItems: 1,
      processedItems: 1,
      total: 1,
      accepted: 1,
      failed: 0,
    };
    const queries: string[] = [];
    await page.route(
      source === "downloads"
        ? "**/api/torrent/jobs?**"
        : "**/api/calibre/transfers?**",
      (r) => {
        queries.push(r.request().url());
        return r.fulfill({
          json: { items: [row], meta: { ...meta, totalItems: 1 } },
        });
      },
    );
    await page.route("**/api/revision-cleanups/cleanup-job", (r) =>
      r.fulfill({ json: { ...job, source, state: "COMPLETED" } }),
    );
    await page.route("**/api/revision-cleanups/cleanup-job/items?**", (r) =>
      r.fulfill({ json: { items: [], meta } }),
    );
    await page.goto(`/${source}/jobs`);
    await expect(
      page.getByRole("button", { name: "Trabajos de limpieza", exact: true }),
    ).toHaveCount(0);
    const table = page.locator("table");
    await expect(
      table.getByRole("columnheader", { name: /^Tipo/ }),
    ).toBeVisible();
    await table.getByRole("button", { name: "Limpieza", exact: true }).click();
    await expect
      .poll(() =>
        queries.some(
          (url) => new URL(url).searchParams.get("type") === "CLEANUP",
        ),
      )
      .toBe(true);
    await table.getByRole("link", { name: "cleanup-job", exact: true }).click();
    await expect(page).toHaveURL(
      new RegExp(`/${source}/jobs/cleanup/cleanup-job$`),
    );
    await expect(
      page.getByRole("heading", { name: "Trabajo de limpieza de revisiones" }),
    ).toBeVisible();
    await page
      .getByRole("link", {
        name:
          source === "downloads"
            ? "Trabajos de descargas"
            : "Trabajos de Calibre",
        exact: true,
      })
      .click();
    await expect(page).toHaveURL(new RegExp(`/${source}/jobs$`));
  });
}
