import { test, expect } from "@playwright/test";
const summary = {
  success: true,
  recordsProcessed: 4,
  recordsCreated: 1,
  recordsUpdated: 1,
  recordsUnchanged: 2,
  errors: 0,
};
test.beforeEach(async ({ page }) => {
  await page.route("**/api/ui/config", (route) =>
    route.fulfill({ json: { defaultLanguage: "en" } }),
  );
});
test("preview, confirmation and update survive navigation and refresh cached catalog", async ({
  page,
}) => {
  let updated = false;
  let updates = 0;
  let previews = 0;
  let release!: () => void;
  const gate = new Promise<void>((resolve) => {
    release = resolve;
  });
  await page.route("**/api/catalog/books?**", (route) =>
    route.fulfill({
      json: {
        items: updated
          ? [
              {
                eplId: 32,
                title: "New book",
                author: "An author",
                language: "en",
                revision: 1,
                publicationYear: 2026,
              },
            ]
          : [],
        meta: {
          page: 0,
          size: 20,
          totalItems: updated ? 1 : 0,
          totalPages: updated ? 1 : 0,
          hasNext: false,
          hasPrevious: false,
        },
      },
    }),
  );
  await page.route("**/api/catalog/import/**", async (route) => {
    expect(route.request().method()).toBe("POST");
    expect(route.request().postData()).toBe(null);
    if (route.request().url().endsWith("/preview")) {
      previews++;
      return route.fulfill({ json: summary });
    }
    expect(route.request().url()).toMatch(/\/update$/);
    updates++;
    await gate;
    updated = true;
    await route.fulfill({ json: summary });
  });
  await page.goto("/catalog");
  await page
    .getByRole("link", { name: "Import the catalog for the first time" })
    .click();
  await page.getByRole("button", { name: "Preview changes" }).click();
  await expect(
    page.getByText("Preview complete. The database has not been modified."),
  ).toBeVisible();
  expect(previews).toBe(1);
  expect(updates).toBe(0);
  await page.getByRole("button", { name: "Download and update" }).click();
  await page.getByRole("button", { name: "Cancel", exact: true }).click();
  expect(updates).toBe(0);
  await page.getByRole("button", { name: "Download and update" }).click();
  await page.getByRole("button", { name: "Update now" }).click();
  await expect(
    page.getByRole("button", { name: "Download and update" }),
  ).toBeDisabled();
  await expect(
    page.getByRole("button", { name: "Preview changes" }),
  ).toBeDisabled();
  await page
    .locator("#sidebar")
    .getByRole("link", { name: "Settings" })
    .click();
  await page.getByRole("main").getByRole("link", { name: "Database" }).click();
  await expect(page.getByRole("status")).toContainText(
    "Downloading and updating",
  );
  release();
  await expect(
    page.getByText("Catalog updated. The new data is now available."),
  ).toBeVisible();
  expect(updates).toBe(1);
  await page
    .locator("#sidebar")
    .getByRole("navigation")
    .getByRole("link", { name: "Catalog", exact: true })
    .click();
  await expect(
    page.getByRole("link", { name: "New book EPL 32" }),
  ).toBeVisible();
});
test("update failure warns about uncertain completion without automatic retry", async ({
  page,
}) => {
  let updates = 0;
  await page.route("**/api/catalog/import/update", (route) => {
    updates++;
    return route.abort();
  });
  await page.goto("/maintenance/catalog");
  await page.getByRole("button", { name: "Download and update" }).click();
  await page.getByRole("button", { name: "Update now" }).click();
  await expect(page.getByRole("alert")).toContainText(
    "The connection to the server was lost.",
  );
  await expect(page.getByRole("alert")).toContainText(
    "It has not been retried automatically.",
  );
  await expect(
    page.getByRole("button", { name: "Download and update" }),
  ).toBeEnabled();
  expect(updates).toBe(1);
});
test("sidebar toggle and language preference survive reload", async ({
  page,
}) => {
  await page.goto("/");
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
  await expect(page.getByRole("switch")).toHaveCount(0);
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
test("invalid deployment language falls back to English even in a Spanish browser", async ({
  page,
}) => {
  await page.route("**/api/ui/config", (route) =>
    route.fulfill({ json: { defaultLanguage: "zz" } }),
  );
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
        items: [
          {
            eplId: 32,
            title: resets ? "Reimported book" : "Old book",
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
    expect(route.request().postDataJSON()).toEqual({ confirm: true });
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
        recordsImported: 1,
      },
    });
  });
  await page.goto("/catalog");
  await expect(
    page.getByRole("link", { name: "Old book EPL 32" }),
  ).toBeVisible();
  await page
    .locator("#sidebar")
    .getByRole("link", { name: "Settings" })
    .click();
  await page.getByRole("main").getByRole("link", { name: "Database" }).click();
  await page
    .getByRole("button", { name: "Reset database", exact: true })
    .click();
  const dialog = page.getByRole("dialog");
  await expect(dialog).toContainText("This action is irreversible.");
  await expect(dialog).toContainText(
    "qBittorrent torrents and files will not be touched.",
  );
  const confirm = dialog.getByRole("button", { name: "Reset and import" });
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
    page.getByText("Database reset and catalog imported from scratch."),
  ).toBeVisible();
  expect(resets).toBe(1);
  await page
    .locator("#sidebar")
    .getByRole("link", { name: "Catalog", exact: true })
    .click();
  await expect(
    page.getByRole("link", { name: "Reimported book EPL 32" }),
  ).toBeVisible();
});

test("busy reset preserves the database and does not retry automatically", async ({
  page,
}) => {
  let resets = 0;
  await page.route("**/api/maintenance/reset", (route) => {
    resets++;
    return route.fulfill({ status: 409, json: { code: "MAINTENANCE_BUSY" } });
  });
  await page.goto("/maintenance/catalog");
  await page
    .getByRole("button", { name: "Reset database", exact: true })
    .click();
  await page
    .getByRole("dialog")
    .getByRole("button", { name: "Reset and import" })
    .click();
  await expect(page.getByRole("alert")).toContainText(
    "The database has not been reset.",
  );
  expect(resets).toBe(1);
  await page.screenshot({
    path: "test-results/reset-busy.png",
    fullPage: true,
  });
});
