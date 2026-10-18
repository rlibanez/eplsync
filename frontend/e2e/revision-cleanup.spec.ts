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
for (const { source, deleteHistory, action } of [
  { source: "downloads", deleteHistory: false, action: "FILES" },
  { source: "downloads", deleteHistory: true, action: "FILES" },
  { source: "downloads", deleteHistory: true, action: "RECORD" },
  { source: "calibre", deleteHistory: false, action: "BOOK" },
] as const) {
  test(`${source}, ${action}, deleteHistory=${deleteHistory}: preview, selection, confirmation and tracking`, async ({
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
      deleteHistory,
      action,
    };
    let creates = 0;
    await page.route("**/api/revision-cleanups/preview", async (r) => {
      expect(r.request().postDataJSON()).toEqual({
        source,
        action: action === "RECORD" ? "KEEP" : action,
        allowUndownloaded: source === "downloads",
        deleteHistory,
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
          confirmDestructive: action !== "RECORD",
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
      await modal.getByRole("textbox", { name: "Acción", exact: true }).click();
      await page
        .getByRole("option", {
          name:
            action === "RECORD"
              ? "Conservar torrents y archivos"
              : "Borrar torrent y archivos",
          exact: true,
        })
        .click();
      if (action === "RECORD")
        await expect(
          modal.getByRole("button", { name: "Previsualizar", exact: true }),
        ).toBeDisabled();
      await expect(
        modal.getByRole("checkbox", {
          name: "Borrar también el registro de EPL Sync",
        }),
      ).not.toBeChecked();
      if (deleteHistory)
        await modal
          .getByRole("checkbox", {
            name: "Borrar también el registro de EPL Sync",
          })
          .check();
      const force = modal.getByRole("checkbox", {
        name: "Permitir la limpieza aunque la revisión superior no esté descargada",
      });
      await expect(force).not.toBeChecked();
      await force.check();
      await expect(modal).not.toContainText(
        "Puedes quedarte sin una copia descargada",
      );
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
    if (action !== "RECORD") {
      await expect(
        modal.getByRole("button", { name: "Crear trabajo de limpieza" }),
      ).toBeDisabled();
      await modal
        .getByRole("checkbox", { name: "Confirmo la eliminación irreversible" })
        .check();
    }
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
test("verification of unconfirmed work never resends deletion", async ({
  page,
}) => {
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
    page.getByText("Información sobre la limpieza", { exact: true }),
  ).toHaveCount(0);
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

test("unconfirmed torrent deletion retries directly and keeps its token after a lost response", async ({
  page,
}) => {
  let current = {
    ...job,
    action: "FILES",
    state: "COMPLETED",
    result: "PARTIAL",
    uncertain: 1,
  };
  await page.route("**/api/revision-cleanups/cleanup-job", (r) =>
    r.fulfill({ json: current }),
  );
  await page.route("**/api/revision-cleanups/cleanup-job/items?**", (r) =>
    r.fulfill({
      json: {
        items: [
          { ...ready, state: current.uncertain ? "UNCERTAIN" : "SUCCEEDED" },
        ],
        meta,
      },
    }),
  );
  const requests: Record<string, unknown>[] = [];
  await page.route("**/api/revision-cleanups/cleanup-job/retry", (r) => {
    requests.push(r.request().postDataJSON());
    if (requests.length === 1)
      return r.fulfill({ status: 500, json: { details: "Respuesta perdida" } });
    current = {
      ...current,
      state: "COMPLETED",
      result: "SUCCEEDED",
      uncertain: 0,
    };
    return r.fulfill({ json: current });
  });
  await page.goto("/downloads/jobs/cleanup/cleanup-job");
  await expect(page.locator(".cleanup-job-status")).toHaveAttribute(
    "data-color",
    "orange",
  );
  await page.locator(".report-collapse > summary").click();
  await expect(page.locator(".cleanup-job-counts > div")).toHaveCount(6);
  await page
    .getByRole("button", {
      name: "Reintentar borrados sin confirmar",
      exact: true,
    })
    .click();
  await expect(page.getByRole("dialog")).toHaveCount(0);
  await expect(
    page.getByText("Respuesta perdida", { exact: true }),
  ).toBeVisible();
  expect(requests).toHaveLength(1);
  await page
    .getByRole("button", {
      name: "Reintentar borrados sin confirmar",
      exact: true,
    })
    .click();
  await expect.poll(() => requests.length).toBe(2);
  expect(requests).toHaveLength(2);
  expect(requests[0]).toEqual(requests[1]);
  expect(requests[0]).toMatchObject({
    confirm: true,
    confirmDestructive: true,
  });
  expect(requests[0].requestId).toMatch(/^[A-Za-z0-9-]{8,64}$/);
});

test("cleanup job summary keeps partial status, metrics and help readable on desktop and mobile", async ({
  page,
}) => {
  await page.route("**/api/revision-cleanups/cleanup-job", (r) =>
    r.fulfill({
      json: {
        ...job,
        action: "FILES",
        state: "COMPLETED",
        result: "PARTIAL",
        allowUndownloaded: true,
        selected: 38,
        succeeded: 28,
        failed: 9,
        uncertain: 1,
      },
    }),
  );
  await page.route("**/api/revision-cleanups/cleanup-job/items?**", (r) =>
    r.fulfill({ json: { items: [{ ...ready, state: "UNCERTAIN" }], meta } }),
  );
  await page.goto("/downloads/jobs/cleanup/cleanup-job");
  const status = page.locator(".cleanup-job-status");
  await expect(status).toContainText("Completado con incidencias");
  await expect(status).toHaveAttribute("data-color", "orange");
  await page.locator(".report-collapse > summary").click();
  await expect(
    page.getByRole("region", { name: "Resumen de la limpieza" }),
  ).toBeVisible();
  await expect(page.locator(".cleanup-job-counts")).toContainText(
    "Sin confirmar",
  );
  await expect(
    page.getByText("Información sobre la limpieza", { exact: true }),
  ).toHaveCount(0);
  await page.screenshot({ path: "/tmp/eplsync-cleanup-job-desktop.png" });
  await page.setViewportSize({ width: 390, height: 844 });
  await expect(status).toBeVisible();
  expect(
    await page.evaluate(
      () => document.documentElement.scrollWidth <= window.innerWidth,
    ),
  ).toBe(true);
  await page.screenshot({
    path: "/tmp/eplsync-cleanup-job-mobile.png",
    animations: "disabled",
  });
  await expect(
    page.getByText("Información sobre la limpieza", { exact: true }),
  ).toHaveCount(0);
  await expect(
    page.getByText("Puedes quedarte sin una copia descargada", {
      exact: false,
    }),
  ).toHaveCount(0);
  await expect(
    page.getByText("Comprobar solo consulta", { exact: false }),
  ).toHaveCount(0);
});

for (const intervention of [false, true]) {
  test(`cleanup explains ${intervention ? "manual intervention" : "automatic waiting"} and links to its blocking job`, async ({
    page,
  }) => {
    await page.route("**/api/revision-cleanups/cleanup-job", (route) =>
      route.fulfill({
        json: {
          ...job,
          state: "COMPLETED",
          result: "FAILED",
          directRemoval: true,
          selected: 1,
          blocked: 1,
        },
      }),
    );
    await page.route("**/api/revision-cleanups/cleanup-job/items?**", (route) =>
      route.fulfill({
        json: {
          items: [
            {
              ...ready,
              state: "BLOCKED",
              message: "Este EPUB sigue pendiente de envío a Calibre",
              blockingCalibreJobId: "protect-job",
              blockingRequiresIntervention: intervention,
            },
          ],
          meta: { ...meta, totalItems: 1 },
        },
      }),
    );
    await page.goto("/downloads/jobs/cleanup/cleanup-job");
    await expect(
      page.getByText(
        intervention ? "Requiere tu intervención" : "Esperando automáticamente",
        { exact: true },
      ),
    ).toBeVisible();
    await expect(
      page.getByRole("link", { name: "Ver trabajo de Calibre", exact: true }),
    ).toHaveAttribute("href", "/calibre/jobs/protect-job");
    await expect(
      page.getByText("Este EPUB sigue pendiente de envío a Calibre", {
        exact: true,
      }),
    ).toBeVisible();
  });
}

test("cleanup hides Calibre work links without Calibre job permissions", async ({
  page,
}) => {
  await page.route("**/api/auth/me", (route) =>
    route.fulfill({
      json: {
        id: "reader",
        username: "reader",
        role: "USER",
        permissions: ["CATALOG_READ", "TORRENT_SYNC", "TORRENT_JOBS_MANAGE"],
        mustChangePassword: false,
      },
    }),
  );
  await page.route("**/api/revision-cleanups/cleanup-job", (route) =>
    route.fulfill({
      json: {
        ...job,
        state: "COMPLETED",
        result: "FAILED",
        directRemoval: true,
        selected: 1,
        blocked: 1,
      },
    }),
  );
  await page.route("**/api/revision-cleanups/cleanup-job/items?**", (route) =>
    route.fulfill({
      json: {
        items: [
          {
            ...ready,
            state: "BLOCKED",
            message: "EPUB protegido por Calibre",
            blockingCalibreJobId: "protect-job",
            blockingRequiresIntervention: true,
          },
        ],
        meta: { ...meta, totalItems: 1 },
      },
    }),
  );
  await page.goto("/downloads/jobs/cleanup/cleanup-job");
  await expect(
    page.getByText("Requiere tu intervención", { exact: true }),
  ).toBeVisible();
  await expect(
    page.getByRole("link", { name: "Ver trabajo de Calibre", exact: true }),
  ).toHaveCount(0);
});
