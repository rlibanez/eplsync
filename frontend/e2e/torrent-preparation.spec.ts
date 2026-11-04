import { test, expect } from "./fixtures";
import type { Page } from "@playwright/test";
const meta = {
  page: 0,
  size: 20,
  totalItems: 65283,
  totalPages: 3265,
  hasNext: true,
  hasPrevious: false,
};
const job = {
  jobId: "large-job",
  type: "DOWNLOAD",
  status: "QUEUED",
  selectedBooks: 65283,
  selectedItems: 65283,
  processedItems: 0,
  accepted: 0,
  alreadyExists: 0,
  skipped: 0,
  failed: 0,
  pending: 65283,
  inFlight: 0,
  cancelled: 0,
  concurrency: 5,
  batchSize: 100,
  interval: "0ms",
  multipleHashes: "all",
  createdAt: "2026-11-04T10:00:00Z",
  updatedAt: "2026-11-04T10:00:00Z",
};
async function setup(page: Page) {
  await page.route("**/api/catalog/books?**", (r) =>
    r.fulfill({
      json: {
        items: [
          {
            eplId: 1,
            title: "Book",
            author: "Author",
            revision: 1,
            download: { items: [] },
          },
        ],
        meta,
      },
    }),
  );
  await page.route("**/api/torrent/options", (r) =>
    r.fulfill({
      json: {
        start: true,
        autoManagement: true,
        savePath: "",
        rename: { enabled: true, pattern: "{title}" },
        category: "",
        tags: [],
        concurrency: 5,
        batchSize: 100,
        interval: "0ms",
        multipleHashes: "all",
      },
    }),
  );
  await page.route("**/api/torrent/client/categories", (r) =>
    r.fulfill({ json: [] }),
  );
  await page.route("**/api/torrent/jobs/large-job", (r) =>
    r.fulfill({ json: job }),
  );
  await page.route("**/api/torrent/jobs/large-job/items?**", (r) =>
    r.fulfill({
      json: { items: [], meta: { ...meta, totalItems: 0, totalPages: 0 } },
    }),
  );
  await page.goto("/catalog");
  await page
    .getByRole("button", { name: "Seleccionar todo (65283)", exact: true })
    .click();
  await page
    .getByRole("button", { name: "Enviar a descargar", exact: true })
    .click();
  await expect(page.getByRole("dialog")).toBeVisible();
}
for (const lost of [false, true]) {
  test(`large selection shows phases and recovers the result with one POST, lost=${lost}`, async ({
    page,
  }) => {
    let posts = 0,
      complete = false,
      phase = "PREPARING",
      operation = "";
    let release!: () => void;
    const finished = new Promise<void>((resolve) => {
      release = resolve;
    });
    await page.route("**/api/torrent/books", async (r) => {
      posts++;
      operation = r.request().headers()["x-eplsync-preparation-id"];
      expect(operation).toBeTruthy();
      if (lost) return r.abort("connectionfailed");
      await finished;
      return r.fulfill({ json: job });
    });
    await page.route("**/api/torrent/preparations/*", (r) => {
      expect(new URL(r.request().url()).pathname).toContain(operation);
      return r.fulfill({
        json: {
          id: operation,
          state: complete ? "COMPLETED" : "RUNNING",
          phase,
          processed: phase === "FINALIZING" ? 65283 : 32600,
          total: 65283,
          items: 32600,
          jobId: "large-job",
          result: complete ? job : null,
          message: null,
        },
      });
    });
    await setup(page);
    const dialog = page.getByRole("dialog");
    await dialog.getByRole("button", { name: /Crear trabajo/ }).click();
    await expect(
      dialog.getByText("Esperando turno para preparar el trabajo…", {
        exact: true,
      }),
    ).toBeVisible();
    await expect(
      dialog.getByText("Preparando el trabajo de descarga…", { exact: true }),
    ).toBeVisible();
    await expect(
      dialog.getByRole("progressbar", {
        name: "Preparación del trabajo de descarga",
      }),
    ).toHaveAttribute("aria-valuenow", /49\./);
    await expect(
      dialog.getByText(
        /32.*600 de 65.*283 libros procesados.*32.*600 elementos preparados/,
      ),
    ).toBeVisible();
    await expect(
      dialog.getByRole("button", { name: "Cancelar", exact: true }),
    ).toBeEnabled();
    await page.screenshot({
      path: `/tmp/eplsync-torrent-progress-${lost}.png`,
    });
    const feedback = dialog.locator(".preparation-feedback");
    await expect(
      feedback.getByText("Creando el trabajo", { exact: true }),
    ).toBeVisible();
    await expect(
      feedback.getByText(/Tiempo transcurrido: [1-9][0-9]* s/),
    ).toBeVisible();
    expect(
      await feedback.evaluate((element) => getComputedStyle(element).textAlign),
    ).toBe("start");
    await page.screenshot({ path: `/tmp/eplsync-torrent-unified-${lost}.png` });
    phase = "FINALIZING";
    await expect(
      dialog.getByText("Guardando el trabajo de descarga…", { exact: true }),
    ).toBeVisible();
    complete = true;
    if (!lost) release();
    await expect(page).toHaveURL(/\/downloads\/jobs\/large-job$/);
    expect(posts).toBe(1);
  });
}
test("an unknown result cannot be submitted again from the same wizard", async ({
  page,
}) => {
  let posts = 0;
  await page.route("**/api/torrent/books", (r) => {
    posts++;
    return r.abort("connectionfailed");
  });
  await page.route("**/api/torrent/preparations/*", (r) =>
    r.fulfill({ status: 404, json: {} }),
  );
  await setup(page);
  const dialog = page.getByRole("dialog");
  await dialog.getByRole("button", { name: /Crear trabajo/ }).click();
  await expect(
    dialog.getByText(/Comprueba los trabajos antes de iniciar otro envío/),
  ).toBeVisible({ timeout: 10000 });
  await expect(
    dialog.getByRole("button", { name: /Crear trabajo/ }),
  ).toBeDisabled();
  await expect(
    dialog.getByRole("link", { name: "Trabajos de descargas", exact: true }),
  ).toHaveAttribute("href", "/downloads/jobs");
  expect(posts).toBe(1);
});

test("an incident reference is not presented as a download job", async ({
  page,
}) => {
  await page.route("**/api/torrent/books", (r) =>
    r.fulfill({
      status: 500,
      json: {
        details: "Ha ocurrido un error inesperado. Referencia: incident-123",
        incidentId: "incident-123",
      },
    }),
  );
  await page.route("**/api/torrent/preparations/*", (r) =>
    r.fulfill({
      json: {
        state: "FAILED",
        phase: "PREPARING",
        processed: 7400,
        total: 65283,
        items: 7480,
        jobId: null,
        result: null,
        message: "No hay un trabajo confirmado",
      },
    }),
  );
  await setup(page);
  const dialog = page.getByRole("dialog");
  await dialog.getByRole("button", { name: /Crear trabajo/ }).click();
  await expect(dialog.getByRole("alert")).toContainText(
    "La referencia identifica la incidencia en el log; no es un identificador de trabajo",
  );
  await expect(
    dialog.getByRole("link", { name: /incident-123|Trabajos de descargas/ }),
  ).toHaveCount(0);
});
for (const permitted of [true, false])
  test(`a retained cancelled preparation links only to its real job with permissions=${permitted}`, async ({
    page,
  }) => {
    if (!permitted)
      await page.route("**/api/auth/me", (r) =>
        r.fulfill({
          json: {
            id: "sender",
            username: "sender",
            role: "USER",
            permissions: ["CATALOG_READ", "TORRENT_SEND"],
          },
        }),
      );
    await page.route("**/api/torrent/books", (r) =>
      r.fulfill({
        status: 422,
        json: {
          details:
            "La preparación no pudo completarse. El trabajo se ha cancelado sin enviar torrents",
        },
      }),
    );
    await page.route("**/api/torrent/preparations/*", (r) =>
      r.fulfill({
        json: {
          state: "FAILED",
          phase: "PREPARING",
          processed: 7400,
          total: 65283,
          items: 7480,
          jobId: "large-job",
          result: null,
          message: "El trabajo se ha cancelado sin enviar torrents",
        },
      }),
    );
    await setup(page);
    const dialog = page.getByRole("dialog");
    await dialog.getByRole("button", { name: /Crear trabajo/ }).click();
    await expect(dialog.getByRole("alert")).toContainText(
      "se ha cancelado sin enviar torrents",
    );
    const link = dialog.getByRole("link", {
      name: "Ver trabajo de descarga: large-job",
      exact: true,
    });
    if (permitted)
      await expect(link).toHaveAttribute("href", "/downloads/jobs/large-job");
    else await expect(link).toHaveCount(0);
  });

test("cancels preparation without resubmitting or opening a job", async ({
  page,
}) => {
  await setup(page);
  let cancelled = false,
    posts = 0;
  await page.route("**/api/torrent/books", async (r) => {
    posts++;
    while (!cancelled) await new Promise((resolve) => setTimeout(resolve, 50));
    await r.fulfill({
      status: 409,
      json: { message: "Preparación cancelada" },
    });
  });
  await page.route("**/api/torrent/preparations/*", (r) =>
    r.fulfill({
      json: {
        id: "test",
        state: cancelled ? "CANCELLED" : "RUNNING",
        phase: "SNAPSHOT",
        processed: 100,
        total: 65283,
        items: 0,
        jobId: null,
        result: null,
        message: null,
      },
    }),
  );
  await page.route("**/api/torrent/preparations/*/cancel", (r) => {
    cancelled = true;
    return r.fulfill({ json: {} });
  });
  const dialog = page.getByRole("dialog");
  await dialog
    .getByRole("button", { name: "Crear trabajo", exact: true })
    .click();
  await expect(
    dialog.getByRole("button", { name: "Cancelar", exact: true }),
  ).toBeEnabled();
  await dialog.getByRole("button", { name: "Cancelar", exact: true }).click();
  await expect(dialog).not.toBeVisible();
  expect(posts).toBe(1);
});
