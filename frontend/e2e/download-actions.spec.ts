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
  await page.route("**/api/torrent/downloads/remove-records", (r) => {
    expect(r.request().postDataJSON()).toEqual({
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
  await dialog
    .getByRole("checkbox", { name: "Eliminar el registro del historial" })
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
