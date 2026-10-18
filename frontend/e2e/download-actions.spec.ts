import { test, expect } from "./fixtures";

test("state refreshes one row and deletes a mixed-book selection", async ({
  page,
}) => {
  let rows = [1, 2].map((id) => ({
    id: String(id),
    eplId: id,
    title: `Libro ${id}`,
    hash: String(id).repeat(40),
    revision: 1,
    status: "DOWNLOADED",
    client: "qbittorrent",
    clientInstanceId: "server",
    origin: "EPLSYNC",
    coverAvailable: false,
  }));
  await page.route("**/api/torrent/downloads?**", (r) =>
    r.fulfill({
      json: {
        items: rows,
        meta: {
          page: 0,
          size: 20,
          totalItems: rows.length,
          totalPages: 1,
          hasNext: false,
          hasPrevious: false,
        },
      },
    }),
  );
  await page.route("**/api/torrent/downloads/refresh-selected", (r) => {
    expect(r.request().postDataJSON()).toEqual({ ids: ["1"] });
    return r.fulfill({
      json: {
        items: [
          { id: "1", status: "DOWNLOADED", cleanupState: null, message: null },
        ],
      },
    });
  });
  await page.route(
    "**/api/torrent/downloads/delete-history?preview=true",
    (r) => {
      expect(r.request().postDataJSON()).toEqual({ ids: ["1", "2"] });
      return r.fulfill({ json: { pendingCleanup: 0 } });
    },
  );
  await page.route("**/api/revision-cleanups/removals", (r) => {
    expect(r.request().postDataJSON().removal).toEqual({
      ids: ["1", "2"],
      clientAction: "keep",
      deleteHistory: true,
      confirm: true,
      confirmFiles: false,
    });
    rows = [];
    return r.fulfill({
      json: {
        items: ["1", "2"].map((id) => ({
          id,
          status: "DOWNLOADED",
          cleanupState: null,
          message: null,
          historyDeleted: true,
        })),
      },
    });
  });
  await page.goto("/downloads");
  await page
    .getByRole("row")
    .filter({ hasText: "Libro 1" })
    .getByRole("button", { name: "Actualizar estado", exact: true })
    .click();
  await expect(page.getByRole("dialog")).toBeVisible();
  await page
    .getByRole("dialog")
    .getByRole("button", { name: "Cerrar", exact: true })
    .click();
  await page
    .getByRole("checkbox", { name: "Seleccionar registros de esta página" })
    .check();
  await page
    .getByRole("button", { name: "Eliminar", exact: true })
    .first()
    .click();
  const dialog = page.getByRole("dialog");
  await expect(
    dialog.locator(".mantine-Modal-title .lucide-trash2"),
  ).toHaveCount(1);
  await dialog
    .getByRole("checkbox", { name: "Eliminar el registro del historial" })
    .check();
  await dialog
    .locator(".app-modal-actions")
    .getByRole("button", { name: "Siguiente", exact: true })
    .click();
  await expect(
    dialog.getByRole("button", { name: "Eliminar", exact: true }),
  ).toBeDisabled();
  await dialog
    .getByRole("checkbox", {
      name: "Confirmo el borrado irreversible de los elementos seleccionados.",
      exact: true,
    })
    .check();
  await dialog.getByRole("button", { name: "Eliminar", exact: true }).click();
  await expect(dialog).toBeVisible();
  await expect(
    page.getByRole("row").filter({ hasText: "Libro 1" }),
  ).toHaveCount(0);
});

test("state synchronization permission exposes refresh but no deletion", async ({
  page,
}) => {
  await page.route("**/api/auth/me", (r) =>
    r.fulfill({
      json: {
        id: "reader",
        username: "reader",
        role: "USER",
        mustChangePassword: false,
        permissions: ["TORRENT_SYNC"],
      },
    }),
  );
  await page.route("**/api/torrent/downloads?**", (r) =>
    r.fulfill({
      json: {
        items: [
          {
            id: "1",
            eplId: 1,
            title: "Libro",
            hash: "A".repeat(40),
            revision: 1,
            status: "DOWNLOADED",
            client: "qbittorrent",
            origin: "EPLSYNC",
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
    }),
  );
  await page.goto("/downloads");
  await expect(
    page.getByRole("button", { name: "Actualizar estado", exact: true }),
  ).toHaveCount(1);
  await expect(
    page.getByRole("button", { name: "Eliminar", exact: true }),
  ).toHaveCount(0);
  await page
    .getByRole("checkbox", { name: "Seleccionar registros de esta página" })
    .check();
  await expect(
    page.getByRole("button", { name: "Actualizar estado", exact: true }),
  ).toHaveCount(2);
  await expect(
    page.getByRole("button", { name: "Eliminar", exact: true }),
  ).toHaveCount(0);
  await expect(
    page.getByRole("button", { name: "Descargar", exact: true }),
  ).toHaveCount(0);
});

test("blocked deletion explains the Calibre dependency and links to its job", async ({
  page,
}) => {
  await page.addInitScript(() =>
    Object.defineProperty(crypto, "randomUUID", { value: undefined }),
  );
  await page.route(
    "**/api/torrent/downloads/delete-history?preview=true",
    (r) => r.fulfill({ json: { pendingCleanup: 0 } }),
  );
  await page.route("**/api/torrent/downloads?**", (r) =>
    r.fulfill({
      json: {
        items: [
          {
            id: "blocked",
            eplId: 1,
            title: "Libro protegido",
            hash: "A".repeat(40),
            revision: 1,
            status: "DOWNLOADED",
            client: "qbittorrent",
            clientInstanceId: "server",
            origin: "EPLSYNC",
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
    }),
  );
  const job = {
    id: "removal-job",
    source: "downloads",
    action: "TORRENT",
    directRemoval: true,
    deleteHistory: false,
    state: "COMPLETED",
    result: "FAILED",
    message: null,
    createdAt: 1792140000000,
    total: 1,
    selected: 1,
    ready: 0,
    excluded: 0,
    pending: 0,
    succeeded: 0,
    blocked: 1,
    failed: 0,
    uncertain: 0,
  };
  const item = {
    id: "blocked",
    eplId: 1,
    title: "Libro protegido",
    revision: "1",
    oldId: "blocked",
    replacementId: "",
    replacementRevision: "",
    formats: [],
    state: "BLOCKED",
    dispatched: false,
    message:
      "No se puede borrar: el trabajo de Calibre está pausado y este EPUB está pendiente de envío.",
    blockingCalibreJobId: "protect-job",
  };
  let submissions = 0;
  await page.route("**/api/revision-cleanups/removals", (r) => {
    submissions++;
    expect(r.request().postDataJSON().requestId).toMatch(/^[a-f0-9-]{36}$/);
    expect(r.request().postDataJSON().removal.ids).toEqual(["blocked"]);
    return r.fulfill({ json: job });
  });
  await page.route("**/api/revision-cleanups/removal-job", (r) =>
    r.fulfill({ json: job }),
  );
  await page.route("**/api/revision-cleanups/removal-job/items?**", (r) =>
    r.fulfill({
      json: {
        items: [item],
        meta: {
          page: 0,
          size: 20,
          totalItems: 1,
          totalPages: 1,
          hasNext: false,
          hasPrevious: false,
        },
      },
    }),
  );
  await page.goto("/downloads");
  await page
    .getByRole("row")
    .filter({ hasText: "Libro protegido" })
    .getByRole("button", { name: "Eliminar", exact: true })
    .click();
  const dialog = page.getByRole("dialog");
  await dialog
    .getByRole("textbox", { name: "En el cliente torrent", exact: true })
    .click();
  await page.getByRole("option", { name: "Solo torrent", exact: true }).click();
  await dialog
    .locator(".app-modal-actions")
    .getByRole("button", { name: "Siguiente", exact: true })
    .click();
  await expect(
    dialog.getByRole("button", { name: "Eliminar", exact: true }),
  ).toBeDisabled();
  await dialog
    .getByRole("checkbox", {
      name: "Confirmo el borrado irreversible de los elementos seleccionados.",
      exact: true,
    })
    .check();
  await dialog.getByRole("button", { name: "Eliminar", exact: true }).click();
  await expect(page).toHaveURL(/\/downloads\/jobs\/cleanup\/removal-job$/);
  await expect(
    page.getByRole("heading", { name: "Trabajo de borrado de descargas" }),
  ).toBeVisible();
  await expect(
    page.getByText(/está pausado y este EPUB está pendiente de envío/),
  ).toBeVisible();
  await expect(
    page.getByRole("link", { name: "Ver trabajo de Calibre" }),
  ).toHaveAttribute("href", "/calibre/jobs/protect-job");
  await expect(
    page.getByRole("button", { name: "Continuar", exact: true }),
  ).toBeVisible();
  expect(submissions).toBe(1);
});

test("deletion previews the whole cross-page selection and submits only checked records", async ({
  page,
}) => {
  const rows = Array.from({ length: 45 }, (_, n) => ({
    id: String(n + 1),
    eplId: n + 1,
    title: `Libro ${n + 1}`,
    hash: String(n + 1).padStart(40, "A"),
    revision: 1,
    status: "DOWNLOADED",
    client: "qbittorrent",
    clientInstanceId: "server",
    origin: "EPLSYNC",
    coverAvailable: false,
  }));
  await page.route("**/api/torrent/downloads?**", (route) => {
    const params = new URL(route.request().url()).searchParams;
    const p = Number(params.get("page") ?? 0),
      size = Number(params.get("size") ?? 20);
    return route.fulfill({
      json: {
        items: rows.slice(p * size, (p + 1) * size),
        meta: {
          page: p,
          size,
          totalItems: 45,
          totalPages: Math.ceil(45 / size),
          hasNext: (p + 1) * size < 45,
          hasPrevious: p > 0,
        },
      },
    });
  });
  await page.route("**/api/torrent/downloads/removal-preview", (route) => {
    expect(route.request().postDataJSON().ids).toHaveLength(45);
    return route.fulfill({ json: rows });
  });
  await page.route(
    "**/api/torrent/downloads/delete-history?preview=true",
    (route) => route.fulfill({ json: { pendingCleanup: 0 } }),
  );
  let sent: string[] = [];
  await page.route("**/api/revision-cleanups/removals", (route) => {
    sent = route.request().postDataJSON().removal.ids;
    return route.fulfill({ json: { id: "deletion-job" } });
  });
  await page.goto("/downloads");
  await page
    .getByRole("button", { name: "Seleccionar todo (45)", exact: true })
    .click();
  await page.getByRole("button", { name: "Siguiente", exact: true }).click();
  await page
    .locator(".selection-toolbar-actions")
    .getByRole("button", { name: "Eliminar", exact: true })
    .click();
  const dialog = page.getByRole("dialog");
  await expect(dialog.locator("tbody tr")).toHaveCount(20);
  await expect(dialog.locator("legend", { hasText: "Libros" })).toBeVisible();
  await expect(dialog.getByRole("columnheader")).toHaveText([
    "",
    "#",
    "Libro",
    "EPL ID",
    "Revisión",
    "Hash",
  ]);
  await expect(dialog.locator("tbody tr").first()).toContainText("Libro 1");
  await expect(dialog.locator(".app-modal-actions")).toContainText(
    "45 registros seleccionados",
  );
  await expect(
    dialog.locator("label").filter({ hasText: /^Acción$/ }),
  ).toHaveCount(0);

  await expect(dialog.locator("tbody tr").first()).toContainText(rows[0].hash);
  await expect(dialog).toContainText("45 registros seleccionados");
  await dialog
    .getByRole("button", { name: "Quitar selección (45)", exact: true })
    .click();
  await expect(
    dialog
      .locator(".app-modal-actions")
      .getByRole("button", { name: "Siguiente", exact: true }),
  ).toBeDisabled();
  await dialog
    .getByRole("button", { name: "Seleccionar disponibles", exact: true })
    .click();
  const pager = dialog.getByRole("navigation", {
    name: "Páginas de la previsualización",
  });
  await pager.getByRole("button", { name: "Siguiente", exact: true }).click();
  await dialog.locator("tbody tr").first().getByRole("checkbox").uncheck();
  await pager.getByRole("button", { name: "Siguiente", exact: true }).click();
  await expect(dialog.locator("tbody tr")).toHaveCount(5);
  await expect(dialog.locator("tbody tr").last()).toContainText(rows[44].hash);
  await expect(dialog).toContainText("44 registros seleccionados");
  const cellBoxes = await dialog
    .locator("tbody tr")
    .first()
    .locator("td")
    .evaluateAll((cells) =>
      cells.map((cell) => {
        const box = cell.getBoundingClientRect();
        return {
          top: box.top,
          height: box.height,
          display: getComputedStyle(cell).display,
        };
      }),
    );
  expect(
    cellBoxes.every(
      (box) =>
        box.display === "table-cell" &&
        Math.abs(box.top - cellBoxes[0].top) < 1 &&
        Math.abs(box.height - cellBoxes[0].height) < 1,
    ),
  ).toBe(true);
  await dialog.screenshot({
    path: "/tmp/eplsync-download-removal-preview.png",
    animations: "disabled",
  });
  const hash = dialog.locator("td.hash-text").first();
  expect(await hash.evaluate((el) => getComputedStyle(el).whiteSpace)).toBe(
    "nowrap",
  );
  await dialog
    .getByRole("checkbox", {
      name: "Eliminar el registro del historial",
      exact: true,
    })
    .check();
  await dialog
    .locator(".app-modal-actions")
    .getByRole("button", { name: "Siguiente", exact: true })
    .click();
  await expect(
    dialog.getByRole("button", { name: "Eliminar", exact: true }),
  ).toBeDisabled();
  await dialog.getByRole("button", { name: "Volver", exact: true }).click();
  await expect(dialog.locator(".app-modal-actions")).toContainText(
    "44 registros seleccionados",
  );
  await expect(dialog.locator("tbody tr")).toHaveCount(5);
  await dialog
    .locator(".app-modal-actions")
    .getByRole("button", { name: "Siguiente", exact: true })
    .click();
  await dialog
    .getByRole("checkbox", {
      name: "Confirmo el borrado irreversible de los elementos seleccionados.",
      exact: true,
    })
    .check();
  await dialog.screenshot({
    path: "/tmp/eplsync-download-removal-confirmation.png",
    animations: "disabled",
  });
  await dialog.getByRole("button", { name: "Eliminar", exact: true }).click();
  await expect.poll(() => sent.length).toBe(44);
  expect(sent).not.toContain("21");
  expect(sent).toContain("1");
  expect(sent).toContain("45");
});

for (const scenario of [
  {
    action: "keep",
    history: true,
    label: "",
    summary:
      "Se eliminarán 2 registros de EPL Sync. Los torrents y sus archivos se conservarán.",
  },
  {
    action: "torrent",
    history: false,
    label: "Solo torrent",
    summary:
      "Se eliminarán 2 torrents. Sus archivos y los registros de EPL Sync se conservarán.",
  },
  {
    action: "torrent",
    history: true,
    label: "Solo torrent",
    summary:
      "Se eliminarán 2 torrents y sus registros de EPL Sync. Los archivos se conservarán.",
  },
  {
    action: "files",
    history: false,
    label: "Torrent y archivos",
    summary:
      "Se eliminarán 2 torrents y sus archivos. Los registros de EPL Sync se conservarán.",
  },
  {
    action: "files",
    history: true,
    label: "Torrent y archivos",
    summary:
      "Se eliminarán 2 torrents, sus archivos y sus registros de EPL Sync.",
  },
])
  test(`unified deletion confirmation: ${scenario.action}, history=${scenario.history}`, async ({
    page,
  }) => {
    const rows = [1, 2].map((n) => ({
      id: String(n),
      eplId: n,
      title: `Libro ${n}`,
      revision: 1,
      hash: String(n).repeat(40),
      status: "DOWNLOADED",
      client: "qbittorrent",
      clientInstanceId: "server",
      origin: "EPLSYNC",
      coverAvailable: false,
    }));
    await page.route("**/api/torrent/downloads?**", (route) =>
      route.fulfill({
        json: {
          items: rows,
          meta: {
            page: 0,
            size: 20,
            totalItems: 2,
            totalPages: 1,
            hasNext: false,
            hasPrevious: false,
          },
        },
      }),
    );
    await page.route(
      "**/api/torrent/downloads/delete-history?preview=true",
      (route) => route.fulfill({ json: { pendingCleanup: 0 } }),
    );
    let sends = 0;
    await page.route("**/api/revision-cleanups/removals", (route) => {
      sends++;
      expect(route.request().postDataJSON().removal).toEqual({
        ids: ["1", "2"],
        clientAction: scenario.action,
        deleteHistory: scenario.history,
        confirm: true,
        confirmFiles: scenario.action === "files",
      });
      return route.fulfill({ json: { id: "deletion-job" } });
    });
    await page.goto("/downloads");
    await page
      .getByRole("checkbox", {
        name: "Seleccionar registros de esta página",
        exact: true,
      })
      .check();
    await page
      .locator(".selection-toolbar-actions")
      .getByRole("button", { name: "Eliminar", exact: true })
      .click();
    const dialog = page.getByRole("dialog");
    if (scenario.history)
      await dialog
        .getByRole("checkbox", {
          name: "Eliminar el registro del historial",
          exact: true,
        })
        .check();
    if (scenario.action !== "keep") {
      await dialog
        .getByRole("textbox", { name: "En el cliente torrent", exact: true })
        .click();
      await page
        .getByRole("option", { name: scenario.label, exact: true })
        .click();
    }
    await dialog
      .locator(".app-modal-actions")
      .getByRole("button", { name: "Siguiente", exact: true })
      .click();
    await expect(dialog.locator("p strong")).toHaveText(scenario.summary);
    await expect(dialog).not.toContainText("2 registros seleccionados");
    await expect(dialog.locator("dl")).toHaveCount(0);
    await expect(dialog.getByRole("checkbox")).toHaveCount(1);
    expect(sends).toBe(0);
    await expect(
      dialog.getByRole("button", { name: "Eliminar", exact: true }),
    ).toBeDisabled();
    await dialog
      .getByRole("checkbox", {
        name: "Confirmo el borrado irreversible de los elementos seleccionados.",
        exact: true,
      })
      .check();
    await dialog.getByRole("button", { name: "Eliminar", exact: true }).click();
    await expect.poll(() => sends).toBe(1);
  });
