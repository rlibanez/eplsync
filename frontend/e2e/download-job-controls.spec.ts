import { test, expect } from "./fixtures";
import type { Page } from "@playwright/test";
const meta = {
  page: 0,
  size: 20,
  totalItems: 4,
  totalPages: 1,
  hasNext: false,
  hasPrevious: false,
};
async function setup(
  page: Page,
  options: { stale?: boolean; fail?: boolean; pages?: boolean } = {},
) {
  const rows = [
    { jobId: "running", type: "DOWNLOAD", status: "RUNNING" },
    { jobId: "paused", type: "UPDATE", status: "PAUSED" },
    { jobId: "done", type: "DOWNLOAD", status: "COMPLETED" },
    { jobId: "cleanup", type: "CLEANUP", status: "RUNNING" },
  ].map((row) => ({
    ...row,
    createdAt: "2026-11-03T20:00:00Z",
    selectedItems: 1,
    selectedBooks: 1,
    processedItems: 0,
    failed: 0,
    pending: 1,
    inFlight: 0,
  }));
  const posts: string[] = [];
  await page.route("**/api/torrent/jobs?**", (r) => {
    const index = Number(
      new URL(r.request().url()).searchParams.get("page") ?? 0,
    );
    return r.fulfill({
      json: {
        items: options.pages ? rows.slice(index * 2, index * 2 + 2) : rows,
        meta: {
          ...meta,
          page: index,
          totalPages: options.pages ? 2 : 1,
          hasNext: options.pages && index === 0,
          hasPrevious: index > 0,
        },
      },
    });
  });
  await page.route(
    /\/api\/(torrent\/jobs|revision-cleanups)\/[^/?]+(?:\/(pause|resume|cancel|continue))?$/,
    (r) => {
      const parts = new URL(r.request().url()).pathname.split("/");
      const isPost = r.request().method() === "POST";
      const id = parts[isPost ? parts.length - 2 : parts.length - 1];
      const row = rows.find((row) => row.jobId === id)!;
      if (isPost) {
        posts.push(`${id}:${parts.at(-1)}`);
        if (options.fail && id === "running")
          return r.fulfill({
            status: 409,
            json: { message: "El destino ha cambiado" },
          });
        row.status =
          parts.at(-1) === "pause"
            ? "PAUSED"
            : parts.at(-1) === "cancel"
              ? "CANCELLED"
              : "QUEUED";
      }
      return r.fulfill({
        json: {
          ...row,
          state: row.status,
          ...(options.stale && !isPost && id === "running"
            ? { status: "COMPLETED" }
            : {}),
        },
      });
    },
  );
  await page.goto("/downloads/jobs");
  await expect(
    page.getByRole("checkbox", { name: "Trabajo: running", exact: true }),
  ).toBeVisible();
  return posts;
}
for (const [id, visible, hidden] of [
  ["running", "Pausar (1)", "Continuar (1)"],
  ["paused", "Continuar (1)", "Pausar (1)"],
  ["done", "Eliminar trabajos", "Cancelar trabajo (1)"],
] as const) {
  test(`selection actions for ${id}`, async ({ page }) => {
    await setup(page);
    await page
      .getByRole("checkbox", { name: `Trabajo: ${id}`, exact: true })
      .check();
    await expect(
      page.getByRole("button", { name: visible, exact: true }),
    ).toBeVisible();
    await expect(
      page.getByRole("button", { name: hidden, exact: true }),
    ).toHaveCount(0);
    await expect(
      page.getByRole("button", { name: "Eliminar trabajos", exact: true }),
    ).toBeVisible();
    if (id === "done")
      await expect(
        page.getByRole("button", { name: /^(Continuar|Pausar) \(/ }),
      ).toHaveCount(0);
  });
}
test("mixed selection acts only on matching jobs and confirms cancellation", async ({
  page,
}) => {
  const posts = await setup(page);
  await page
    .getByRole("checkbox", { name: "Seleccionar esta página", exact: true })
    .check();
  await page
    .getByRole("button", { name: "Continuar (1)", exact: true })
    .click();
  await expect.poll(() => posts).toEqual(["paused:resume"]);
  await page
    .getByRole("button", { name: "Cancelar trabajo (3)", exact: true })
    .click();
  const dialog = page.getByRole("dialog", {
    name: "Cancelar trabajo",
    exact: true,
  });
  await expect(dialog).toContainText("3 trabajos seleccionados compatibles");
  expect(posts).toEqual(["paused:resume"]);
  await dialog
    .getByRole("button", { name: "Cancelar trabajo", exact: true })
    .click();
  await expect.poll(() => posts.length).toBe(4);
  expect(posts).toEqual([
    "paused:resume",
    "running:cancel",
    "paused:cancel",
    "cleanup:cancel",
  ]);
});
test("fresh terminal state is skipped without a control request", async ({
  page,
}) => {
  const posts = await setup(page, { stale: true });
  await page
    .getByRole("checkbox", { name: "Trabajo: running", exact: true })
    .check();
  await page.getByRole("button", { name: "Pausar (1)", exact: true }).click();
  await expect(
    page.getByText("Aplicados: 0 · Ya no aplicables: 1 · Fallidos: 0", {
      exact: true,
    }),
  ).toBeVisible();
  expect(posts).toEqual([]);
});
test("a failed control does not prevent the other eligible actions", async ({
  page,
}) => {
  const posts = await setup(page, { fail: true });
  await page
    .getByRole("checkbox", { name: "Seleccionar esta página", exact: true })
    .check();
  await page
    .getByRole("button", { name: "Cancelar trabajo (3)", exact: true })
    .click();
  await page
    .getByRole("dialog")
    .getByRole("button", { name: "Cancelar trabajo", exact: true })
    .click();
  await expect(
    page.getByText("Aplicados: 2 · Ya no aplicables: 0 · Fallidos: 1", {
      exact: true,
    }),
  ).toBeVisible();
  expect(posts).toEqual(["running:cancel", "paused:cancel", "cleanup:cancel"]);
});
test("selection controls retain jobs on other pages", async ({ page }) => {
  const posts = await setup(page, { pages: true });
  await page
    .getByRole("checkbox", { name: "Seleccionar esta página", exact: true })
    .check();
  await page.getByRole("button", { name: "Siguiente", exact: true }).click();
  await page
    .getByRole("checkbox", { name: "Trabajo: cleanup", exact: true })
    .check();
  await expect(
    page.getByRole("button", { name: "Pausar (1)", exact: true }),
  ).toBeVisible();
  await page
    .getByRole("button", { name: "Continuar (1)", exact: true })
    .click();
  await expect.poll(() => posts).toEqual(["paused:resume"]);
});
test("cleanup-only permissions do not expose normal job controls", async ({
  page,
}) => {
  await page.route("**/api/auth/me", (r) =>
    r.fulfill({
      json: {
        authenticated: true,
        id: "admin",
        username: "admin",
        role: "USER",
        permissions: ["TORRENT_SYNC", "DOWNLOADS_DELETE"],
      },
    }),
  );
  await setup(page);
  await expect(
    page.getByRole("checkbox", { name: "Trabajo: running", exact: true }),
  ).toBeDisabled();
  await page
    .getByRole("checkbox", { name: "Trabajo: cleanup", exact: true })
    .check();
  await expect(
    page.getByRole("button", { name: "Pausar (1)", exact: true }),
  ).toHaveCount(0);
  await expect(
    page.getByRole("button", { name: "Cancelar trabajo (1)", exact: true }),
  ).toBeVisible();
});
