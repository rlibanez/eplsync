import { test, expect } from "./fixtures";
test("cover settings reject incompatible times while editing", async ({
  page,
}) => {
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
  await page.route("**/api/catalog/covers/task", (r) =>
    r.fulfill({ json: null }),
  );
  let saves = 0;
  await page.route("**/api/settings/covers", (r) => {
    if (r.request().method() === "PUT") saves++;
    return r.fulfill({
      json: {
        section: "covers",
        fields: [
          {
            key: "catalog.cover-check.connect-timeout",
            type: "duration",
            value: "3s",
          },
          {
            key: "catalog.cover-check.request-timeout",
            type: "duration",
            value: "3s",
          },
          {
            key: "catalog.cover-check.batch-timeout",
            type: "duration",
            value: "4s",
          },
          { key: "catalog.cover-check.concurrency", type: "number", value: 4 },
        ],
      },
    });
  });
  await page.goto("/settings/covers");
  const section = page
    .locator("section")
    .filter({
      has: page.getByRole("heading", {
        name: "Configuración de portadas",
        exact: true,
      }),
    });
  await section
    .getByRole("textbox", { name: "Tiempo de conexión (segundos)" })
    .fill("10");
  await section
    .getByRole("textbox", { name: "Tiempo por URL (segundos)" })
    .fill("20");
  await section
    .getByRole("textbox", { name: "Tiempo por lote (segundos)" })
    .fill("10");
  await expect(section.getByRole("alert")).toContainText(
    "tiempo de conexión ≤ tiempo por URL ≤ tiempo por lote",
  );
  await expect(
    section.getByRole("button", { name: "Guardar", exact: true }),
  ).toBeDisabled();
  await section
    .getByRole("textbox", { name: "Tiempo por lote (segundos)" })
    .fill("30");
  await expect(section.getByRole("alert")).toHaveCount(0);
  await expect(
    section.getByRole("button", { name: "Guardar", exact: true }),
  ).toBeEnabled();
  expect(saves).toBe(0);
  await expect(section.locator("fieldset").first()).toHaveCSS(
    "border-top-width",
    "0px",
  );
});
