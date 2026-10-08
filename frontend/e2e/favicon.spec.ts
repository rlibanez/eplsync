import { test, expect } from "./fixtures";

test("public favicon adapts to the stored theme", async ({ page }) => {
  await page.addInitScript(() => {
    localStorage.setItem("eplsync:palette", "blue");
    localStorage.setItem("eplsync:scheme", "light");
  });
  const errors: string[] = [];
  page.on("pageerror", (error) => errors.push(error.message));
  await page.goto("/settings/account");
  await expect(page.locator('link[rel="icon"]')).toHaveAttribute(
    "href",
    /^data:image\/svg\+xml,/,
  );
  const icon = await page.locator('link[rel="icon"]').getAttribute("href");
  expect(decodeURIComponent(icon!)).toContain("hsl(215 35% 90%)");
  expect(decodeURIComponent(icon!)).toContain("hsl(215 55% 29%)");
  expect(errors).toEqual([]);
  const response = await page.request.get("/favicon.svg");
  expect(response.ok()).toBe(true);
  expect(response.headers()["content-type"]).toContain("image/svg+xml");
});

test("an unavailable favicon does not prevent the application from loading", async ({
  page,
}) => {
  await page.route("**/favicon.svg", (route) => route.abort());
  const errors: string[] = [];
  page.on("pageerror", (error) => errors.push(error.message));
  await page.goto("/settings/account");
  await expect(
    page.getByLabel("Correo electrónico", { exact: true }),
  ).toBeVisible();
  await expect(page.locator('link[rel="icon"]')).toHaveAttribute(
    "href",
    "/favicon.svg",
  );
  expect(errors).toEqual([]);
});
