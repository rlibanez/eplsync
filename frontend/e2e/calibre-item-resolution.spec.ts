import { test, expect, type Page } from "./fixtures";

async function fixture(page: Page, files = true, state = "FAILED") {
  let itemState = state;
  let jobState = "COMPLETED";
  const requests: Record<string, unknown>[] = [];
  const job = () => ({
    id: "job",
    createdAt: "2026-11-03T12:00:00Z",
    updatedAt: "2026-11-03T12:01:00Z",
    username: "Owner",
    library: "main",
    state: jobState,
    message: null,
    total: 1,
    pending: itemState === "PENDING" ? 1 : 0,
    accepted: itemState === "ACCEPTED" ? 1 : 0,
    skipped: 0,
    failed: itemState === "FAILED" ? 1 : 0,
    uncertain: 0,
    cancelled: 0,
    omitted: itemState === "OMITTED" ? 1 : 0,
  });
  await page.route("https://images.epublibre.org/**", (r) => r.abort());
  await page.route("**/api/calibre/transfers/job", (r) =>
    r.fulfill({ json: job() }),
  );
  await page.route("**/api/calibre/transfers/job/items?**", (r) =>
    r.fulfill({
      json: {
        items: [
          {
            id: "item",
            position: 0,
            name: "Libro de prueba",
            author: "Autor",
            eplId: 32,
            revision: "1.2",
            state: itemState,
            calibreId: null,
            recordId: null,
            coverUrl: "javascript:invalid",
            message:
              itemState === "OMITTED"
                ? "Omitido por el usuario"
                : "La descarga requiere una selección manual",
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
  await page.route("**/api/job-retries/calibre/job", (r) =>
    r.fulfill({
      json: {
        source: null,
        retries: [],
        terminal: jobState === "COMPLETED",
        failed: 0,
        reason: null,
        attemptCount: 0,
      },
    }),
  );
  await page.route(
    "**/api/calibre/transfers/job/items/item/resolution",
    async (r) => {
      if (r.request().method() === "GET")
        return r.fulfill({
          json: {
            token: "a".repeat(64),
            files: files
              ? [
                  {
                    recordId: "record",
                    index: 0,
                    name: "primero.epub",
                    size: 10,
                  },
                  {
                    recordId: "record",
                    index: 1,
                    name: "segundo.epub",
                    size: 20,
                  },
                ]
              : [],
            problem: files
              ? null
              : "La descarga contiene archivos RAR, PDF, pero ningún EPUB disponible",
          },
        });
      const body = r.request().postDataJSON();
      requests.push(body);
      itemState = body.action === "omit" ? "OMITTED" : "PENDING";
      jobState = body.action === "omit" ? "COMPLETED" : "QUEUED";
      return r.fulfill({ json: job() });
    },
  );
  return requests;
}

test("chooses one EPUB and retries in the original job only after confirmation", async ({
  page,
}) => {
  const requests = await fixture(page);
  await page.goto("/calibre/jobs/job");
  await page.getByRole("button", { name: "Resolver", exact: true }).click();
  const dialog = page.getByRole("dialog", { name: "Resolver", exact: true });
  await expect(
    dialog.getByRole("button", { name: "Guardar y reintentar" }),
  ).toBeDisabled();
  await dialog.getByLabel("Archivo EPUB", { exact: true }).click();
  await page.getByRole("option", { name: /segundo.epub/ }).click();
  await dialog
    .getByLabel("He comprobado que el EPUB corresponde a este libro.", {
      exact: true,
    })
    .check();
  await dialog.getByRole("button", { name: "Guardar y reintentar" }).click();
  await expect(dialog).not.toBeVisible();
  expect(requests).toHaveLength(1);
  expect(requests[0]).toMatchObject({
    action: "retry",
    recordId: "record",
    index: 1,
    name: "segundo.epub",
    size: 20,
    confirm: true,
  });
  await expect(page).toHaveURL(/\/calibre\/jobs\/job$/);
});

test("RAR and PDF can be omitted with an explicit reason and a distinct completion count", async ({
  page,
}) => {
  const requests = await fixture(page, false);
  await page.goto("/calibre/jobs/job");
  await page.getByRole("button", { name: "Resolver", exact: true }).click();
  const dialog = page.getByRole("dialog", { name: "Resolver", exact: true });
  await expect(dialog).toContainText("RAR, PDF");
  await expect(
    dialog.getByLabel("Archivo EPUB", { exact: true }),
  ).toBeDisabled();
  await dialog.getByLabel("Acción", { exact: true }).click();
  await page.getByRole("option", { name: "Omitir", exact: true }).click();
  await expect(
    dialog.getByRole("button", { name: "Omitir", exact: true }),
  ).toBeDisabled();
  await dialog.getByLabel("Motivo de la omisión").click();
  for (const label of [
    "No necesito enviarlo",
    "Lo gestionaré manualmente",
    "Contenido no adecuado",
    "Otro",
  ])
    await expect(
      page.getByRole("option", { name: label, exact: true }),
    ).toBeVisible();
  await page
    .getByRole("option", { name: "Lo gestionaré manualmente", exact: true })
    .click();
  await dialog
    .getByLabel("Comentario (opcional)")
    .fill("Lo revisaré fuera del envío");
  await dialog
    .getByLabel("Confirmo la omisión de este elemento.", { exact: true })
    .check();
  await dialog.getByRole("button", { name: "Omitir", exact: true }).click();
  await expect(dialog).not.toBeVisible();
  expect(requests).toHaveLength(1);
  expect(requests[0]).toMatchObject({
    action: "omit",
    reason: "MANUAL",
    note: "Lo revisaré fuera del envío",
    confirm: true,
  });
  await expect(
    page.getByText("Terminado con omisiones", { exact: true }),
  ).toBeVisible();
  await page.locator("details.report-collapse summary").click();
  await expect(page.locator(".report-collapse-body")).toContainText(
    "Omitidos por el usuario",
  );
  await expect(
    page.getByRole("progressbar", { name: "Progreso", exact: true }),
  ).toHaveAttribute("aria-valuenow", "100");
  await expect(
    page.getByRole("button", { name: "Resolver", exact: true }),
  ).toHaveCount(0);
});

test("successful items do not offer the failed-item resolver", async ({
  page,
}) => {
  await fixture(page, true, "ACCEPTED");
  await page.goto("/calibre/jobs/job");
  await expect(
    page.getByText("Libro de prueba", { exact: true }),
  ).toBeVisible();
  await expect(
    page.getByRole("button", { name: "Resolver", exact: true }),
  ).toHaveCount(0);
});

test("the item resolver requires file-read permission", async ({ page }) => {
  await fixture(page);
  await page.route("**/api/auth/me", (r) =>
    r.fulfill({
      json: {
        id: "user",
        username: "Reader",
        role: "USER",
        mustChangePassword: false,
        permissions: [
          "CALIBRE_SEND",
          "CALIBRE_JOBS_MANAGE",
          "CALIBRE_READ",
          "CATALOG_READ",
        ],
      },
    }),
  );
  await page.goto("/calibre/jobs/job");
  await expect(
    page.getByText("Libro de prueba", { exact: true }),
  ).toBeVisible();
  await expect(
    page.getByRole("button", { name: "Resolver", exact: true }),
  ).toHaveCount(0);
});
