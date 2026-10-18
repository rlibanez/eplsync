import { test, expect } from "./fixtures";

for (const source of ["downloads", "calibre"] as const) {
  test(`${source}: delete eligible jobs, show blockers and revalidate the preview`, async ({
    page,
  }) => {
    let rows = [
      {
        id: "done",
        jobId: "done",
        type: source === "downloads" ? "DOWNLOAD" : "SEND",
        state: "COMPLETED",
        status: "COMPLETED",
      },
      {
        id: "paused",
        jobId: "paused",
        type: source === "downloads" ? "UPDATE" : "SEND",
        state: "PAUSED",
        status: "PAUSED",
      },
      {
        id: "cleanup",
        jobId: "cleanup",
        type: "CLEANUP",
        state: "COMPLETED",
        status: "COMPLETED",
      },
    ];
    const endpoint =
      source === "downloads" ? "torrent/jobs" : "calibre/transfers";
    await page.route(`**/api/${endpoint}?**`, (r) =>
      r.fulfill({
        json: {
          items: rows.map((row) => ({
            ...row,
            createdAt: "2026-10-17T00:00:00Z",
            library: "main",
            total: 1,
            selectedBooks: 1,
            selectedItems: 1,
            processedItems: 1,
            accepted: 1,
            failed: 0,
          })),
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
    let previews = 0,
      deletions = 0;
    await page.route(
      `**/api/settings/work/${source}/deletions/preview`,
      async (r) => {
        previews++;
        const jobs = r.request().postDataJSON().jobs;
        expect(jobs).toHaveLength(3);
        expect(
          jobs.find((row: { id: string }) => row.id === "cleanup").cleanup,
        ).toBe(true);
        await r.fulfill({
          json: {
            items: jobs.map((row: { id: string }) => ({
              ...row,
              allowed: row.id !== "paused",
              deleted: false,
              reason:
                row.id === "paused"
                  ? "Cancela primero el trabajo pausado"
                  : null,
              relatedSource: null,
              relatedId: null,
            })),
          },
        });
      },
    );
    await page.route(`**/api/settings/work/${source}/deletions`, async (r) => {
      deletions++;
      const jobs = r.request().postDataJSON().jobs;
      expect(jobs.map((row: { id: string }) => row.id)).toEqual([
        "done",
        "cleanup",
      ]);
      rows = rows.filter((row) => row.id !== "done");
      await r.fulfill({
        json: {
          items: jobs.map((row: { id: string }) => ({
            ...row,
            allowed: row.id === "done",
            deleted: row.id === "done",
            reason:
              row.id === "cleanup"
                ? "Hay un borrado pendiente de confirmar"
                : null,
            relatedSource: row.id === "cleanup" ? "calibre" : null,
            relatedId: row.id === "cleanup" ? "related" : null,
            relatedCleanup: false,
          })),
        },
      });
    });
    await page.goto(`/${source}/jobs`);
    await expect(
      page.getByRole("button", { name: "Eliminar trabajos", exact: true }),
    ).toHaveCount(0);
    await page
      .getByRole("checkbox", { name: "Seleccionar esta página", exact: true })
      .check();
    await page
      .getByRole("button", { name: "Eliminar trabajos", exact: true })
      .click();
    const dialog = page.getByRole("dialog", {
      name: "Eliminar trabajos",
      exact: true,
    });
    await expect(dialog).toContainText("Se pueden eliminar 2 trabajos");
    await expect(dialog).toContainText("Cancela primero el trabajo pausado");
    await expect(dialog).toContainText(
      "Se conservarán los torrents, archivos, registros de Descargas y libros de Calibre",
    );
    await expect(dialog.getByRole("button", {name:"Cancelar",exact:true})).toBeVisible();
    await expect(dialog.getByRole("button", {name:"Cerrar",exact:true})).toHaveCount(0);
    expect(deletions).toBe(0);
    await dialog
      .getByRole("button", { name: "Eliminar trabajos", exact: true })
      .click();
    await expect(dialog).toContainText("Trabajos eliminados: 1");
    await expect(dialog).toContainText("Hay un borrado pendiente de confirmar");
    await expect(
      dialog.getByRole("link", { name: "Ver trabajo relacionado" }),
    ).toHaveAttribute("href", "/calibre/jobs/related");
    await dialog.getByRole("button", { name: "Cerrar", exact: true }).click();
    await expect(page.locator("tbody tr")).toHaveCount(2);
    expect(previews).toBe(1);
    expect(deletions).toBe(1);
    await page.screenshot({ path: `test-results/${source}-work-deletion.png` });
  });
}

test("Calibre sending permission alone cannot delete job history", async ({
  page,
}) => {
  await page.route("**/api/auth/me", (r) =>
    r.fulfill({
      json: {
        id: "sender",
        username: "sender",
        role: "USER",
        permissions: ["CALIBRE_READ", "CALIBRE_SEND"],
      },
    }),
  );
  await page.route("**/api/calibre/transfers?**", (r) =>
    r.fulfill({
      json: {
        items: [
          {
            id: "done",
            state: "COMPLETED",
            type: "SEND",
            total: 0,
            accepted: 0,
            failed: 0,
          },
        ],
        meta: { page: 0, size: 20, totalItems: 1, totalPages: 1 },
      },
    }),
  );
  await page.goto("/calibre/jobs");
  await expect(page.locator("tbody tr")).toHaveCount(1);
  await expect(page.getByRole("checkbox")).toHaveCount(0);
  await expect(
    page.getByRole("button", { name: "Eliminar trabajos", exact: true }),
  ).toHaveCount(0);
});
