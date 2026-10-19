import { test, expect } from "./fixtures";

for (const differentFormats of [false, true]) {
  test(`combined library cleanup changes the retained copy and reviews formats: ${differentFormats}`, async ({
    page,
  }) => {
    const requests: Record<string, any>[] = [];
    let generation = 0;
    let keep = "20";
    let reviewed = false;
    const meta = {
      page: 0,
      size: 20,
      totalItems: 2,
      totalPages: 1,
      hasNext: false,
      hasPrevious: false,
    };
    const job = () => ({
      id: "library-plan",
      source: "calibre",
      action: "BOOK",
      state: "PREVIEW",
      library: "main",
      libraryCleanup: true,
      total: 2,
      ready: differentFormats && !reviewed ? 1 : 2,
      excluded: differentFormats && !reviewed ? 1 : 0,
      selected: differentFormats && !reviewed ? 1 : 2,
    });
    await page.route("**/api/calibre/books?**", (r) =>
      r.fulfill({ json: { items: [], meta: { ...meta, totalItems: 0 } } }),
    );
    await page.route("**/api/settings/calibre/snapshot", (r) =>
      r.fulfill({ json: { snapshot: null } }),
    );
    const status = () => ({
      job: job(),
      state: "READY",
      version: generation,
      processed: 1,
      total: 1,
      reviewRequired: differentFormats && !reviewed ? 1 : 0,
      message: null,
    });
    await page.route(
      "**/api/revision-cleanups/calibre-library/prepare",
      (r) => {
        requests.push(r.request().postDataJSON());
        generation = 1;
        return r.fulfill({ json: status() });
      },
    );
    await page.route("**/api/revision-cleanups/library-plan/preparation", (r) =>
      r.fulfill({ json: status() }),
    );
    await page.route(
      "**/api/revision-cleanups/library-plan/library-groups?**",
      (r) =>
        r.fulfill({
          json: {
            items: [
              {
                key: "1:2",
                eplId: 1,
                revision: "2",
                retainedId: keep,
                title: "Libro",
                formats:
                  keep === "21" && differentFormats
                    ? ["EPUB"]
                    : ["EPUB", "PDF"],
                requiresChoice: differentFormats && !reviewed,
              },
            ],
            meta: { ...meta, totalItems: 1 },
          },
        }),
    );
    await page.route(
      "**/api/revision-cleanups/library-plan/library-copies?**",
      (r) =>
        r.fulfill({
          json: {
            items: [
              { id: "20", title: "Libro", formats: ["EPUB", "PDF"] },
              {
                id: "21",
                title: "Libro",
                formats: differentFormats ? ["EPUB"] : ["EPUB", "PDF"],
              },
            ],
            meta,
          },
        }),
    );
    await page.route(
      "**/api/revision-cleanups/library-plan/library-retained",
      (r) => {
        requests.push(r.request().postDataJSON());
        keep = r.request().postDataJSON().copyId;
        reviewed = true;
        generation++;
        return r.fulfill({ json: status() });
      },
    );
    await page.route(
      "**/api/revision-cleanups/library-plan/library-items?**",
      (r) =>
        r.fulfill({
          json: {
            items: [
              {
                id: `old-${generation}`,
                eplId: 1,
                title: "Libro anterior",
                revision: "1",
                oldId: "10",
                replacementId: keep,
                replacementRevision: "2",
                formats: ["EPUB"],
                reason: "OLD_REVISION",
                state: "READY",
                previewSelected: true,
              },
              {
                id: `duplicate-${generation}`,
                eplId: 1,
                title: "Libro duplicado",
                revision: "2",
                oldId: keep === "20" ? "21" : "20",
                replacementId: keep,
                replacementRevision: "2",
                formats: ["EPUB"],
                reason: "DUPLICATE",
                state: differentFormats && !reviewed ? "EXCLUDED" : "READY",
                previewSelected: !differentFormats || reviewed,
              },
            ],
            meta,
          },
        }),
    );
    await page.route("**/api/revision-cleanups/library-plan/items?**", (r) =>
      r.fulfill({ json: { items: [], meta: { ...meta, totalItems: 0 } } }),
    );
    let submitted: any;
    await page.route(
      "**/api/revision-cleanups/library-plan/library-confirm",
      (r) => {
        submitted = r.request().postDataJSON();
        return r.fulfill({ json: { ...job(), state: "QUEUED" } });
      },
    );
    await page.route("**/api/revision-cleanups/library-plan", (r) =>
      r.fulfill({ json: { ...job(), state: "QUEUED" } }),
    );
    await page.goto("/calibre");
    await page
      .getByRole("button", { name: "Limpiar biblioteca", exact: true })
      .click();
    const modal = page.getByRole("dialog");
    await expect(
      modal.getByRole("checkbox", {
        name: "Limpiar revisiones antiguas",
        exact: true,
      }),
    ).toBeChecked();
    await expect(
      modal.getByRole("checkbox", { name: "Limpiar duplicados", exact: true }),
    ).toBeChecked();
    await modal
      .getByRole("checkbox", {
        name: "Limpiar revisiones antiguas",
        exact: true,
      })
      .uncheck();
    await modal
      .getByRole("checkbox", { name: "Limpiar duplicados", exact: true })
      .uncheck();
    await expect(
      modal.getByRole("button", { name: "Previsualizar", exact: true }),
    ).toBeDisabled();
    await modal
      .getByRole("checkbox", {
        name: "Limpiar revisiones antiguas",
        exact: true,
      })
      .check();
    await modal
      .getByRole("checkbox", { name: "Limpiar duplicados", exact: true })
      .check();
    await modal
      .getByRole("button", { name: "Previsualizar", exact: true })
      .click();
    await expect(
      modal.getByRole("columnheader", { name: "Motivo", exact: true }),
    ).toBeVisible();
    await expect(
      modal.getByRole("cell", { name: "Revisión antigua", exact: true }),
    ).toBeVisible();
    await expect(
      modal.getByRole("cell", { name: "Duplicado", exact: true }),
    ).toBeVisible();
    if (differentFormats) {
      await expect(
        modal.getByText(/Las copias tienen formatos diferentes/),
      ).toBeVisible();
      await expect(
        modal.getByRole("button", { name: "Siguiente", exact: true }),
      ).toBeDisabled();
    }
    await modal
      .getByRole("textbox", { name: "EPL 1 · r2", exact: true })
      .click();
    await page.getByRole("option", { name: /Calibre ID 21/ }).click();
    await expect.poll(() => requests.length).toBe(2);
    expect(requests[1]).toMatchObject({
      version: 1,
      groupKey: "1:2",
      copyId: "21",
    });
    await expect(
      modal.getByRole("checkbox", { name: "Seleccionar Libro duplicado" }),
    ).toBeChecked();
    if (!differentFormats)
      await modal.screenshot({
        path: "/tmp/eplsync-library-cleanup-preview.png",
      });
    await modal.getByRole("button", { name: "Siguiente", exact: true }).click();
    await modal
      .getByRole("checkbox", { name: "He revisado la selección" })
      .check();
    await modal
      .getByRole("checkbox", { name: "Confirmo la eliminación irreversible" })
      .check();
    await modal
      .getByRole("button", { name: "Crear trabajo de limpieza" })
      .click();
    await expect(page).toHaveURL(/\/calibre\/jobs\/cleanup\/library-plan$/);
    expect(submitted).toEqual({
      version: 2,
      confirm: true,
      confirmDestructive: true,
    });
    await expect(
      page.getByRole("heading", { name: "Trabajo de limpieza de biblioteca" }),
    ).toBeVisible();
  });
}

test("large library preview uses server pages and preserves selections outside the visible page", async ({
  page,
}) => {
  let selected = new Set(Array.from({ length: 1005 }, (_, i) => `row-${i}`));
  const fetched: number[] = [];
  let cancelled = false;
  const meta = (index: number, total: number) => ({
    page: index,
    size: 20,
    totalItems: total,
    totalPages: Math.ceil(total / 20),
    hasNext: (index + 1) * 20 < total,
    hasPrevious: index > 0,
  });
  const job = () => ({
    id: "large-library",
    source: "calibre",
    action: "BOOK",
    state: "PREVIEW",
    library: "main",
    libraryCleanup: true,
    total: 1005,
    ready: 1005,
    selected: selected.size,
    excluded: 0,
  });
  const status = () => ({
    job: job(),
    state: "READY",
    version: 1,
    processed: 1005,
    total: 1005,
    reviewRequired: 0,
    message: null,
  });
  await page.route("**/api/calibre/books?**", (r) =>
    r.fulfill({ json: { items: [], meta: meta(0, 0) } }),
  );
  await page.route("**/api/settings/calibre/snapshot", (r) =>
    r.fulfill({ json: { snapshot: null } }),
  );
  await page.route("**/api/revision-cleanups/calibre-library/prepare", (r) => {
    expect(r.request().postDataJSON()).toMatchObject({
      oldRevisions: true,
      duplicates: true,
    });
    return r.fulfill({ json: status() });
  });
  await page.route("**/api/revision-cleanups/large-library/preparation", (r) =>
    r.fulfill({ json: status() }),
  );
  await page.route(
    "**/api/revision-cleanups/large-library/library-groups?**",
    (r) => r.fulfill({ json: { items: [], meta: meta(0, 0) } }),
  );
  await page.route(
    "**/api/revision-cleanups/large-library/library-items?**",
    (r) => {
      const params = new URL(r.request().url()).searchParams;
      expect(params.get("size")).toBe("20");
      const index = Number(params.get("page"));
      fetched.push(index);
      return r.fulfill({
        json: {
          items: Array.from(
            { length: Math.min(20, 1005 - index * 20) },
            (_, i) => {
              const n = index * 20 + i;
              return {
                id: `row-${n}`,
                eplId: n + 1,
                title: `Libro ${n}`,
                revision: "1",
                oldId: String(n + 1),
                replacementId: "99999",
                replacementRevision: "2",
                formats: ["EPUB"],
                reason: "OLD_REVISION",
                state: "READY",
                previewSelected: selected.has(`row-${n}`),
              };
            },
          ),
          meta: meta(index, 1005),
        },
      });
    },
  );
  await page.route(
    "**/api/revision-cleanups/large-library/library-selection",
    (r) => {
      const change = r.request().postDataJSON();
      if (change.all)
        selected = change.selected
          ? new Set(Array.from({ length: 1005 }, (_, i) => `row-${i}`))
          : new Set();
      else
        for (const id of change.ids)
          if (change.selected) selected.add(id);
          else selected.delete(id);
      return r.fulfill({ json: status() });
    },
  );
  await page.route(
    "**/api/revision-cleanups/large-library/library-cancel",
    (r) => {
      cancelled = true;
      return r.fulfill({ json: { ...status(), state: "CANCELLED" } });
    },
  );
  await page.goto("/calibre");
  await page
    .getByRole("button", { name: "Limpiar biblioteca", exact: true })
    .click();
  const modal = page.getByRole("dialog");
  await modal
    .getByRole("button", { name: "Previsualizar", exact: true })
    .click();
  await expect(
    modal.getByRole("checkbox", { name: "Seleccionar Libro 0", exact: true }),
  ).toBeChecked();
  expect(fetched).toEqual([0]);
  await modal
    .getByRole("checkbox", { name: "Seleccionar Libro 0", exact: true })
    .click();
  await expect.poll(() => selected.size).toBe(1004);
  const pager = modal.getByRole("navigation");
  await pager.getByRole("button", { name: "Siguiente", exact: true }).click();
  await expect(
    modal.getByRole("checkbox", { name: "Seleccionar Libro 20", exact: true }),
  ).toBeChecked();
  await pager.getByRole("button", { name: "Anterior", exact: true }).click();
  await expect(
    modal.getByRole("checkbox", { name: "Seleccionar Libro 0", exact: true }),
  ).not.toBeChecked();
  await modal.getByRole("button", { name: /Quitar selección/ }).click();
  await expect.poll(() => selected.size).toBe(0);
  await modal
    .getByRole("button", { name: "Seleccionar disponibles", exact: true })
    .click();
  await expect.poll(() => selected.size).toBe(1005);
  await modal.getByRole("button", { name: "Cancelar", exact: true }).click();
  await expect(modal).not.toBeVisible();
  expect(cancelled).toBe(true);
});

test("background preview can be cancelled before it finishes", async ({
  page,
}) => {
  const meta = { page: 0, size: 20, totalItems: 0, totalPages: 0 };
  let cancelled = false;
  const status = {
    job: {
      id: "preparing",
      source: "calibre",
      action: "BOOK",
      state: "PREVIEW",
      libraryCleanup: true,
      selected: 0,
    },
    state: "RUNNING",
    version: 1,
    processed: 500,
    total: 73000,
    reviewRequired: 0,
    message: null,
  };
  await page.route("**/api/calibre/books?**", (r) =>
    r.fulfill({ json: { items: [], meta } }),
  );
  await page.route("**/api/settings/calibre/snapshot", (r) =>
    r.fulfill({ json: { snapshot: null } }),
  );
  await page.route("**/api/revision-cleanups/calibre-library/prepare", (r) =>
    r.fulfill({ json: status }),
  );
  await page.route("**/api/revision-cleanups/preparing/preparation", (r) =>
    r.fulfill({ json: status }),
  );
  await page.route("**/api/revision-cleanups/preparing/library-cancel", (r) => {
    cancelled = true;
    return r.fulfill({ json: { ...status, state: "CANCELLED" } });
  });
  await page.goto("/calibre");
  await page
    .getByRole("button", { name: "Limpiar biblioteca", exact: true })
    .click();
  const modal = page.getByRole("dialog");
  await modal
    .getByRole("button", { name: "Previsualizar", exact: true })
    .click();
  await expect(modal.getByRole("progressbar")).toBeVisible();
  await expect(
    modal.getByRole("button", { name: "Siguiente", exact: true }),
  ).toBeDisabled();
  await modal.getByRole("button", { name: "Cancelar", exact: true }).click();
  await expect(modal).not.toBeVisible();
  expect(cancelled).toBe(true);
});
