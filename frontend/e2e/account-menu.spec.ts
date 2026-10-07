import { test, expect } from "./fixtures";

test("sidebar account menu exposes profile and keeps logout separate from folding", async ({
  page,
}) => {
  let logouts = 0;
  await page.route("**/api/auth/logout", (route) => {
    logouts++;
    return route.fulfill({ json: {} });
  });
  await page.goto("/settings/account");
  const sidebar = page.locator("#sidebar");
  const account = sidebar.getByRole("button", { name: "admin", exact: true });
  await expect(account).toBeVisible();
  await account.click();
  await expect(
    page.getByRole("menuitem", { name: "Mi cuenta", exact: true }),
  ).toBeVisible();
  await expect(
    page.getByRole("menuitem", { name: "Cerrar sesión", exact: true }),
  ).toBeVisible();
  expect(logouts).toBe(0);
  await page.getByRole("menuitem", { name: "Mi cuenta", exact: true }).click();
  await expect(page).toHaveURL(/\/settings\/account$/);
  await sidebar
    .getByRole("button", { name: "Plegar menú lateral", exact: true })
    .click();
  await account.click();
  await expect(
    page.getByRole("menuitem", { name: "Cerrar sesión", exact: true }),
  ).toBeVisible();
  expect(logouts).toBe(0);
});
