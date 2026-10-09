import { test, expect, emitEvent, type Page } from "./fixtures";
test.use({ locale: "en-GB" });

const summary = {
  success: true,
  recordsProcessed: 4,
  recordsCreated: 1,
  recordsUpdated: 1,
  recordsUnchanged: 2,
  errors: 0,
  missingBooks: 2,
  preview: {
    token: "saved-preview",
    expiresAt: "2099-10-03T04:00:00Z",
    sourceModifiedAt: "2026-10-02T04:00:00",
  },
};
async function chooseUrl(page: Page) {
  await page
    .getByRole("button", { name: "Import catalog", exact: true })
    .click();
  const dialog = page.getByRole("dialog");
  await expect(dialog.getByRole("textbox", { name: "URL" })).toHaveValue(
    "https://example.test/catalog.zip",
  );
  await dialog.getByRole("button", { name: "Next", exact: true }).click();
  return dialog;
}
async function previewUrl(page: Page) {
  const dialog = await chooseUrl(page);
  await dialog
    .getByRole("button", { name: "Preview changes", exact: true })
    .click();
}
test.beforeEach(async ({ page }) => {
  await page.route("**/api/catalog/covers/config", (r) =>
    r.fulfill({
      json: {
        connectTimeoutMs: 3000,
        requestTimeoutMs: 3000,
        batchTimeoutMs: 4000,
        concurrency: 4,
      },
    }),
  );
  await page.route("**/api/settings/covers", (r) =>
    r.fulfill({ json: { section: "covers", fields: [] } }),
  );
  await page.route("**/api/catalog/import/source", (route) =>
    route.fulfill({
      json: { defaultUrl: "https://example.test/catalog.zip", archive: null },
    }),
  );

  await page.route("**/api/catalog/import/preview/discard", (route) =>
    route.fulfill({ status: 204 }),
  );
  await page.route("**/api/catalog/import/preview/saved-preview", (route) =>
    route.fulfill({ json: summary }),
  );
  await page.route("**/api/catalog/import/metadata", (route) =>
    route.fulfill({ json: { metadata: null } }),
  );
  await page.route("**/api/catalog/covers/task", (route) =>
    route.fulfill({ json: { task: null } }),
  );
});
test("editable URL wizard closes while importing and result survives navigation", async ({
  page,
}) => {
  let release!: () => void;
  let requests = 0;
  const gate = new Promise<void>((resolve) => {
    release = resolve;
  });
  await page.route("**/api/catalog/import/run", async (route) => {
    requests++;
    expect(route.request().postDataJSON()).toEqual({
      source: "URL",
      url: "https://example.test/custom.zip",
      dryRun: false,
    });
    await gate;
    await route.fulfill({ json: { ...summary, preview: null } });
    await emitEvent(page, {});
  });
  await page.goto("/settings/catalog");
  await page
    .getByRole("button", { name: "Import catalog", exact: true })
    .click();
  const dialog = page.getByRole("dialog");
  await expect(dialog.getByRole("textbox", { name: "URL" })).toHaveValue(
    "https://example.test/catalog.zip",
  );
  await dialog
    .getByRole("textbox", { name: "URL" })
    .fill("https://example.test/custom.zip");
  await dialog.getByRole("button", { name: "Next", exact: true }).click();
  await dialog.screenshot({ path: "test-results/import-wizard-operation.png" });
  await dialog
    .getByRole("button", { name: "Update catalog", exact: true })
    .click();
  await expect(dialog).toBeHidden();
  await expect(
    page.getByRole("button", { name: "Import catalog", exact: true }),
  ).toBeDisabled();
  await page
    .locator("#sidebar")
    .getByRole("link", { name: "Settings", exact: true })
    .click();
  await page
    .getByRole("main")
    .getByRole("link", { name: "Catalog", exact: true })
    .click();
  await expect(page.getByRole("main").getByRole("status")).toContainText(
    "Updating catalog",
  );
  release();
  await expect(
    page.getByRole("heading", { name: "Update results", exact: true }),
  ).toBeVisible();
  await page
    .getByRole("button", { name: "Close results", exact: true })
    .click();
  await expect(
    page.getByRole("heading", { name: "Update results", exact: true }),
  ).toBeHidden();
  expect(requests).toBe(1);
  // A per-import URL must not become the server's default.
  await chooseUrl(page);
  await page
    .getByRole("dialog")
    .getByRole("button", { name: "Back", exact: true })
    .click();
  await page
    .getByRole("dialog")
    .getByRole("button", { name: "Cancel", exact: true })
    .click();
});
test("update failure warns about uncertain completion without automatic retry", async ({
  page,
}) => {
  let updates = 0;
  await page.route("**/api/catalog/import/run", (route) => {
    updates++;
    return route.abort();
  });
  await page.goto("/maintenance/catalog");
  const wizard = await chooseUrl(page);
  await wizard
    .getByRole("button", { name: "Update catalog", exact: true })
    .click();
  await expect(page.locator(".notification-toasts")).toContainText(
    "The connection to the server was lost.",
  );
  await expect(page.locator(".notification-toasts")).toContainText(
    "It has not been retried automatically.",
  );
  await expect(
    page.getByRole("button", { name: "Import catalog" }),
  ).toBeEnabled();
  expect(updates).toBe(1);
});
test("sidebar toggle and language preference survive reload", async ({
  page,
}) => {
  await page.goto("/settings/general");
  await page.getByRole("button", { name: "Collapse sidebar" }).click();
  await expect(page.locator(".shell")).toHaveClass(/sidebar-collapsed/);
  await expect(
    page.getByRole("button", { name: "Expand sidebar" }),
  ).toHaveAttribute("aria-expanded", "false");
  await page
    .locator("#sidebar")
    .getByRole("link", { name: "Settings" })
    .click();
  await expect(
    page.getByRole("heading", { name: "Settings", exact: true }),
  ).toBeVisible();
  await page.reload();
  await expect(page.locator(".shell")).toHaveClass(/sidebar-collapsed/);
  await page
    .getByRole("main")
    .getByLabel("Interface language")
    .selectOption("es");
  await expect(
    page.getByRole("heading", { name: "Ajustes", exact: true }),
  ).toBeVisible();
  await expect(
    page.getByRole("button", { name: "Usar idioma del despliegue" }),
  ).toHaveCount(0);
  await expect(
    page.getByRole("switch", { name: /Plegar|Collapse/ }),
  ).toHaveCount(0);
  await expect(
    page
      .locator("#sidebar")
      .getByRole("button", { name: "Desplegar menú lateral" }),
  ).toBeVisible();
  await page.getByRole("button", { name: "Desplegar menú lateral" }).click();
  await expect(page.locator(".shell")).not.toHaveClass(/sidebar-collapsed/);
  await expect(page.locator(".topline").getByRole("button")).toHaveCount(0);
  await expect(page.locator(".sidebar-note")).toHaveCount(0);
  await expect(page.locator(".edition")).toHaveCount(0);
  await page.screenshot({
    path: "test-results/settings-desktop.png",
    fullPage: true,
  });
});
test("initial settings language follows English browser preferences", async ({
  page,
}) => {
  await page.goto("/settings");
  await expect(page.locator("html")).toHaveAttribute("lang", "en");
  await expect(
    page.getByRole("main").getByLabel("Interface language"),
  ).toHaveValue("en");
});

test("full reset requires the red confirmation and clears cached catalog after success", async ({
  page,
}) => {
  let resets = 0;
  await page.route("**/api/catalog/books?**", (route) =>
    route.fulfill({
      json: {
        items: resets
          ? []
          : [
              {
                eplId: 32,
                title: "Old book",
                author: "Author",
                language: "en",
                revision: 1,
                publicationYear: 2026,
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
  await page.route("**/api/maintenance/reset", (route) => {
    expect(route.request().method()).toBe("POST");
    expect(route.request().postDataJSON()).toEqual({
      confirm: true,
      eraseUsersAndSettings: false,
      fullResetConfirmation: "",
    });
    resets++;
    return route.fulfill({
      json: {
        success: true,
        catalogBooks: 1,
        downloads: 2,
        jobs: 3,
        jobItems: 4,
        updatePlans: 5,
        cleanupRecords: 6,
        metadataRecords: 1,
        events: 2,
      },
    });
  });
  await page.goto("/catalog");
  await expect(page.getByRole("link", { name: "Old book" })).toBeVisible();
  await page
    .locator("#sidebar")
    .getByRole("link", { name: "Settings" })
    .click();
  await page
    .getByRole("main")
    .getByRole("link", { name: "Database", exact: true })
    .click();
  await page
    .getByRole("button", { name: "Reset database", exact: true })
    .click();
  const dialog = page.getByRole("dialog");
  await expect(dialog).toContainText("This action cannot be undone.");
  await expect(dialog).toContainText("Client torrents and files will be kept.");
  const confirm = dialog.getByRole("button", { name: "Empty database" });
  await expect(confirm).toHaveCSS("background-color", "rgb(224, 49, 49)");
  await dialog.getByRole("button", { name: "Cancel", exact: true }).click();
  expect(resets).toBe(0);
  await page
    .getByRole("button", { name: "Reset database", exact: true })
    .click();
  await confirm.click();
  await expect(
    page.getByRole("heading", { name: "Reset result" }),
  ).toBeVisible();
  await expect(
    page
      .locator(".notification-toasts")
      .getByText("Database reset completed.", { exact: true }),
  ).toBeVisible();
  expect(resets).toBe(1);
  const resetPanel = page.locator(".danger-panel");
  await expect(
    resetPanel.getByRole("heading", { name: "Reset result" }),
  ).toBeVisible();
  await resetPanel.getByRole("button", { name: "Close reset result" }).click();
  await expect(page.getByRole("heading", { name: "Reset result" })).toHaveCount(
    0,
  );
  await expect(
    resetPanel.getByRole("button", { name: "Reset database", exact: true }),
  ).toBeEnabled();
  expect(resets).toBe(1);
  await page
    .locator("#sidebar")
    .getByRole("link", { name: "Catalog", exact: true })
    .click();
  await expect(page.getByRole("link", { name: "Old book" })).toHaveCount(0);
});

test("busy reset preserves the database and does not retry automatically", async ({
  page,
}) => {
  let resets = 0;
  await page.route("**/api/maintenance/reset", (route) => {
    resets++;
    return route.fulfill({ status: 409, json: { code: "MAINTENANCE_BUSY" } });
  });
  await page.goto("/settings/database");
  await page
    .getByRole("button", { name: "Reset database", exact: true })
    .click();
  await page
    .getByRole("dialog")
    .getByRole("button", { name: "Empty database" })
    .click();
  await expect(page.locator(".notification-toasts")).toContainText(
    "The database has not been reset.",
  );
  expect(resets).toBe(1);
  await page.screenshot({
    path: "test-results/reset-busy.png",
    fullPage: true,
  });
});

test("applied import has a single metadata summary and dismissible notice", async ({
  page,
}) => {
  const metadata = {
    sourceModifiedAt: "2026-09-30T04:00:50",
    importedAt: "2026-10-01T06:33:18Z",
    importMode: "UPDATE",
    durationMs: 4247,
    totalRows: 4,
    insertedRows: 1,
    updatedRows: 1,
    unchangedRows: 2,
    errorRows: 0,
    sourceFileName: "catalog.csv",
    sourceUrl: "https://example.test/catalog.zip",
    sourceSha256: "a".repeat(64),
  };
  await page.route("**/api/catalog/import/metadata", (route) =>
    route.fulfill({ json: { metadata } }),
  );
  await page.route("**/api/catalog/import/run", async (route) => {
    await route.fulfill({ json: { ...summary, metadata } });
    await emitEvent(page, {});
  });
  await page.goto("/settings/catalog");
  const current = page.locator("section").filter({
    has: page.getByRole("heading", { name: "Current catalog", exact: true }),
  });
  await expect(current.getByRole("link")).toHaveCount(0);
  const wizard = await chooseUrl(page);
  await wizard
    .getByRole("button", { name: "Update catalog", exact: true })
    .click();
  const notice = page.getByRole("status").filter({ hasText: "Completed" });
  await expect(notice).toBeVisible();
  await expect(page.getByText(metadata.sourceUrl, { exact: true })).toHaveCount(
    1,
  );
  await expect(
    page.getByText("2026-09-30 04:00:50", { exact: true }),
  ).toHaveCount(1);
  await expect(
    page.getByRole("heading", { name: "Update result", exact: true }),
  ).toHaveCount(0);
  await notice
    .getByRole("button", { name: "Dismiss notification", exact: true })
    .click();
  await expect(notice).toBeHidden();
  await expect(
    page.getByRole("heading", { name: "Current catalog" }),
  ).toBeVisible();
});

test("absent books are reviewed inside a scrollable dialog and deleted only after confirmation", async ({
  page,
}) => {
  let deletes = 0;
  const items = Array.from({ length: 50 }, (_, i) => ({
    eplId: i + 100,
    title: `Absent book ${i + 100}`,
    revision: 1.2,
  }));
  await page.route("**/api/catalog/import/missing/preview", (route) =>
    route.fulfill({
      json: { token: "review", total: 51, size: 50, page: 0, items },
    }),
  );
  await page.route("**/api/catalog/import/missing/review?**", (route) =>
    route.fulfill({
      json: {
        token: "review",
        total: 51,
        size: 50,
        page: 1,
        items: [{ eplId: 150, title: "Last absent book", revision: 1 }],
      },
    }),
  );
  await page.route("**/api/catalog/import/missing/delete", (route) => {
    deletes++;
    expect(route.request().postDataJSON()).toEqual({
      token: "review",
      confirm: true,
    });
    return route.fulfill({ json: { deleted: 51 } });
  });
  await page.goto("/settings/catalog");
  await page
    .getByRole("button", { name: "Delete absent books", exact: true })
    .click();
  const dialog = page.getByRole("dialog");
  await expect(dialog).toContainText("51 books");
  await expect(dialog.getByRole("table")).toContainText("Absent book 100");
  expect(deletes).toBe(0);
  const scroll = dialog.locator(".missing-books-list");
  expect(
    await scroll.evaluate((node) => node.scrollHeight > node.clientHeight),
  ).toBe(true);
  await dialog.screenshot({ path: "test-results/absent-books-dialog.png" });
  await dialog.getByRole("button", { name: "2", exact: true }).click();
  await expect(dialog.getByRole("table")).toContainText("Last absent book");
  await dialog.getByRole("button", { name: "Cancel", exact: true }).click();
  expect(deletes).toBe(0);
  await page
    .getByRole("button", { name: "Delete absent books", exact: true })
    .click();
  await dialog
    .getByRole("button", { name: "Delete books", exact: true })
    .click();
  await expect(dialog).toBeHidden();
  expect(deletes).toBe(1);
});

test("preview summary fits its content and reports absent books", async ({
  page,
}) => {
  await page.route("**/api/catalog/import/run", (route) =>
    route.fulfill({ json: summary }),
  );
  await page.goto("/settings/catalog");
  await previewUrl(page);
  const preview = page.locator(".import-preview-result");
  await expect(preview).toContainText("Absent from CSV");
  expect(
    await preview.evaluate(
      (node) =>
        node.getBoundingClientRect().width <
        node.parentElement!.getBoundingClientRect().width,
    ),
  ).toBe(true);
  await preview.getByRole("button", { name: "Discard preview" }).click();
  await expect(preview).toBeHidden();
});

test("catalog preview uses server events without duplicate notifications and shows starts by default", async ({
  page,
}) => {
  await page.route("**/api/catalog/import/run", async (route) => {
    await emitEvent(page, {
      action: "PREVIEW",
      outcome: "STARTED",
      details: { dryRun: true },
    });
    await route.fulfill({
      headers: { "X-EPLSync-Operation-Id": "test-operation" },
      json: summary,
    });
  });
  await page.goto("/settings/catalog");
  await previewUrl(page);
  const toasts = page.locator(".notification-toasts");
  await expect(
    toasts.getByText("Comparing the CSV with the current catalog…", {
      exact: true,
    }),
  ).toHaveCount(1);
  await expect(page.locator(".import-preview-result")).toBeVisible();
  await emitEvent(page, { action: "PREVIEW", details: { dryRun: true } });
  await expect(
    toasts.getByText("Preview complete. The database has not been modified.", {
      exact: true,
    }),
  ).toHaveCount(1);
});

test("retained preview survives reload, applies without download and X discards", async ({
  page,
}) => {
  let downloads = 0,
    applies = 0,
    discards = 0;
  await page.route("**/api/catalog/import/run", (route) => {
    downloads++;
    return route.fulfill({ json: summary });
  });
  await page.route("**/api/catalog/import/preview/apply", (route) => {
    applies++;
    expect(route.request().postDataJSON()).toEqual({ token: "saved-preview" });
    return route.fulfill({ json: { ...summary, preview: null } });
  });
  await page.route("**/api/catalog/import/preview/discard", (route) => {
    discards++;
    expect(route.request().postDataJSON()).toEqual({ token: "saved-preview" });
    return route.fulfill({ status: 204 });
  });
  await page.goto("/settings/catalog");
  await previewUrl(page);
  const box = page.locator(
    ".import-preview-result[aria-labelledby=import-preview-heading]",
  );
  await expect(box.getByText("Expires at:", { exact: false })).toHaveCount(0);
  await page.reload();
  await expect(
    box.getByRole("button", { name: "Update catalog", exact: true }),
  ).toBeEnabled();
  await box.screenshot({ path: "test-results/retained-preview.png" });
  await box
    .getByRole("button", { name: "Update catalog", exact: true })
    .click();
  await expect(box).toBeHidden();
  expect(downloads).toBe(1);
  expect(applies).toBe(1);
  await previewUrl(page);
  await box
    .getByRole("button", { name: "Discard preview", exact: true })
    .click();
  await expect(box).toBeHidden();
  expect(discards).toBe(1);
  await page.reload();
  await expect(box).toBeHidden();
});

test("stale preview is recalculated from the retained ZIP before applying", async ({
  page,
}) => {
  let downloads = 0,
    applies = 0,
    recalculated = false;
  await page.route("**/api/catalog/import/run", (route) => {
    downloads++;
    return route.fulfill({ json: summary });
  });
  await page.route("**/api/catalog/import/preview/apply", (route) => {
    applies++;
    return recalculated
      ? route.fulfill({ json: summary })
      : route.fulfill({ status: 409, json: { code: "PREVIEW_STALE" } });
  });
  await page.route("**/api/catalog/import/preview/refresh", (route) => {
    expect(route.request().postDataJSON()).toEqual({ token: "saved-preview" });
    recalculated = true;
    return route.fulfill({ json: { ...summary, recordsUpdated: 3 } });
  });
  await page.goto("/settings/catalog");
  await previewUrl(page);
  const box = page.locator(
    ".import-preview-result[aria-labelledby=import-preview-heading]",
  );
  await box
    .getByRole("button", { name: "Update catalog", exact: true })
    .click();
  await expect(box.getByRole("alert")).toContainText("The catalog has changed");
  await expect(
    box.getByRole("button", { name: "Update catalog", exact: true }),
  ).toBeDisabled();
  await box.getByRole("button", { name: "Recalculate", exact: true }).click();
  await box
    .getByRole("button", { name: "Update catalog", exact: true })
    .click();
  await expect(box).toBeHidden();
  expect(downloads).toBe(1);
  expect(applies).toBe(2);
});

test("failed discard retains the summary and expired apply requests a new preview", async ({
  page,
}) => {
  await page.route("**/api/catalog/import/run", (route) =>
    route.fulfill({ json: summary }),
  );
  await page.route("**/api/catalog/import/preview/discard", (route) =>
    route.fulfill({ status: 500, json: {} }),
  );
  await page.route("**/api/catalog/import/preview/apply", (route) =>
    route.fulfill({ status: 410, json: { code: "PREVIEW_EXPIRED" } }),
  );
  await page.goto("/settings/catalog");
  await previewUrl(page);
  const box = page.locator(
    ".import-preview-result[aria-labelledby=import-preview-heading]",
  );
  await box.getByRole("button", { name: "Discard", exact: true }).click();
  await expect(box).toBeVisible();
  await expect(
    box.getByRole("button", { name: "Update catalog", exact: true }),
  ).toBeEnabled();
  await box
    .getByRole("button", { name: "Update catalog", exact: true })
    .click();
  await expect(box).toBeHidden();
  await expect(
    page.getByRole("button", { name: "Import catalog", exact: true }),
  ).toBeEnabled();
});

test("saved ZIP details, reuse and dismissing a preview retain the archive", async ({
  page,
}) => {
  const archive = {
    id: "saved-zip",
    name: "catalog.zip",
    sourceUrl: "https://example.test/catalog.zip",
    storedAt: "2026-10-02T12:00:00Z",
    expiresAt: "2099-10-03T12:00:00Z",
    size: 35127296,
    sha256: "a".repeat(64),
    csvName: "catalog.csv",
    csvModifiedAt: "2026-10-02T04:00:00",
  };
  await page.route("**/api/catalog/import/source", (route) =>
    route.fulfill({
      json: { defaultUrl: "https://example.test/catalog.zip", archive },
    }),
  );
  let runs = 0;
  await page.route("**/api/catalog/import/run", (route) => {
    runs++;
    expect(route.request().postDataJSON()).toEqual({
      source: "SAVED",
      archiveId: "saved-zip",
      dryRun: true,
    });
    return route.fulfill({ json: summary });
  });
  await page.goto("/settings/catalog");
  await page
    .getByRole("button", { name: "Import catalog", exact: true })
    .click();
  const dialog = page.getByRole("dialog");
  await expect(
    dialog.getByRole("radio", { name: "Saved archive" }),
  ).toBeChecked();
  await expect(dialog).toContainText("2026-10-02 04:00:00");
  await expect(dialog).toContainText(archive.sha256);
  await dialog.screenshot({ path: "test-results/import-wizard-saved.png" });
  await dialog.getByRole("button", { name: "Next", exact: true }).click();
  await dialog
    .getByRole("button", { name: "Preview changes", exact: true })
    .click();
  await page
    .getByRole("button", { name: "Discard preview", exact: true })
    .click();
  await page
    .getByRole("button", { name: "Import catalog", exact: true })
    .click();
  await expect(
    dialog.getByRole("radio", { name: "Saved archive" }),
  ).toBeChecked();
  await dialog.getByRole("radio", { name: "URL", exact: true }).check();
  await expect(dialog).not.toContainText(
    "even if the new download or upload fails",
  );
  await dialog.getByRole("button", { name: "Cancel", exact: true }).click();
  expect(runs).toBe(1);
});
test("local ZIP is uploaded as multipart and invalid file cannot advance", async ({
  page,
}) => {
  let uploads = 0;
  await page.route("**/api/catalog/import/run", (route) => {
    uploads++;
    expect(route.request().headers()["content-type"]).toContain(
      "multipart/form-data",
    );
    const body = route.request().postDataBuffer()!.toString();
    expect(body).toContain('filename="books.zip"');
    expect(body).toContain('name="options"');
    expect(body).toContain('"dryRun":false');
    return route.fulfill({ json: { ...summary, preview: null } });
  });
  await page.goto("/settings/catalog");
  await page
    .getByRole("button", { name: "Import catalog", exact: true })
    .click();
  const dialog = page.getByRole("dialog");
  await dialog
    .getByRole("radio", { name: "Upload ZIP file", exact: true })
    .check();
  const input = dialog.locator("input[type=file]");
  await input.setInputFiles({
    name: "books.txt",
    mimeType: "text/plain",
    buffer: Buffer.from("invalid"),
  });
  await expect(
    dialog.getByRole("button", { name: "Next", exact: true }),
  ).toBeDisabled();
  await input.setInputFiles({
    name: "books.zip",
    mimeType: "application/zip",
    buffer: Buffer.from("mock transport ZIP"),
  });
  await dialog.getByRole("button", { name: "Next", exact: true }).click();
  await dialog
    .getByRole("button", { name: "Update catalog", exact: true })
    .click();
  await expect(dialog).toBeHidden();
  await expect(
    page.getByRole("heading", { name: "Update results", exact: true }),
  ).toBeVisible();
  expect(uploads).toBe(1);
});
