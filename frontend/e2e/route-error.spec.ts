import { test, expect } from "./fixtures";

test("failed route imports show a recovery page and reload preserves the URL", async ({ page }) => {
  let unavailable = true;
  let moduleRequests = 0;
  await page.route("**/src/features/settings/Settings.tsx*", async (route) => {
    moduleRequests++;
    if (unavailable) return route.abort("connectionrefused");
    return route.continue();
  });
  const path = "/settings/general?tab=appearance#controls";
  await page.goto(path);
  await expect(page.getByRole("heading", { name: "No se ha podido cargar esta página" })).toBeVisible();
  await expect(page.getByRole("alert")).toContainText("La aplicación puede haberse actualizado");
  await expect(page.locator("body")).not.toContainText("Unexpected Application Error!");
  await expect(page.locator("body")).not.toContainText("TypeError:");
  await expect(page.getByRole("button", { name: "Recargar aplicación" })).toBeEnabled();
  // The boundary offers recovery without triggering reload loops while the server is unavailable.
  expect(moduleRequests).toBe(1);
  await page.screenshot({ path: "/tmp/eplsync-route-error-desktop.png" });
  await page.setViewportSize({ width: 360, height: 760 });
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true);
  await page.screenshot({ path: "/tmp/eplsync-route-error-mobile.png" });
  unavailable = false;
  await page.getByRole("button", { name: "Recargar aplicación" }).click();
  await expect(page.getByRole("heading", { name: "Aspecto", exact: true })).toBeVisible();
  await expect(page).toHaveURL(new RegExp("/settings/general\\?tab=appearance#controls$"));
  expect(moduleRequests).toBe(2);
});

test("unexpected route failures show a safe generic message", async ({ page }) => {
  await page.route("**/src/features/settings/Settings.tsx*", route => route.fulfill({
    contentType: "application/javascript",
    body: "throw new Error('private diagnostic detail');",
  }));
  await page.goto("/settings/general");
  await expect(page.getByRole("heading", { name: "No se ha podido mostrar esta página" })).toBeVisible();
  await expect(page.getByRole("alert")).toContainText("Se ha producido un error inesperado");
  await expect(page.locator("body")).not.toContainText("private diagnostic detail");
  await expect(page.getByRole("button", { name: "Recargar aplicación" })).toBeVisible();
});
