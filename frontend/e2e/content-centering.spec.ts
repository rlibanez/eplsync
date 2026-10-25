import { test, expect } from "./fixtures";

for (const width of [1440, 2560]) {
  test(`content keeps the same margins at ${width}px with either sidebar width`, async ({ page }) => {
    await page.setViewportSize({ width, height: 1000 });
    await page.goto("/settings/general");
    await expect(page.locator("main")).toBeVisible();
    await expect(page.locator("#sidebar")).toBeVisible();
    const geometry = () => page.evaluate(() => {
      const main = document.querySelector("main")!;
      const rect = main.getBoundingClientRect();
      const sidebar = document.querySelector("#sidebar")!.getBoundingClientRect();
      const style = getComputedStyle(main);
      return {
        left: rect.left + parseFloat(style.paddingLeft) - sidebar.right,
        right: innerWidth - rect.right + parseFloat(style.paddingRight),
        padding: parseFloat(style.paddingLeft),
        width: rect.width,
        available: innerWidth - sidebar.right,
        overflow: document.documentElement.scrollWidth > innerWidth,
      };
    });
    for (const label of ["Plegar menú lateral", "Desplegar menú lateral"]) {
      const value = await geometry();
      expect(Math.abs(value.left - value.right)).toBeLessThan(1);
      expect(value.left).toBe(28);
      expect(value.right).toBe(28);
      expect(value.padding).toBe(28);
      expect(value.width).toBe(value.available);
      expect(value.overflow).toBe(false);
      await page.getByRole("button", { name: label, exact: true }).click();
    }
  });
}

test("mobile content retains equal compact margins without reserving sidebar space", async ({ page }) => {
  await page.setViewportSize({ width: 390, height: 844 });
  await page.goto("/settings/general");
  const main = page.locator("main");
  await expect(main).toHaveCSS("padding-left", "18px");
  await expect(main).toHaveCSS("padding-right", "18px");
  const box = await main.boundingBox();
  expect(box!.x).toBe(0);
  expect(box!.width).toBe(390);
  expect(await page.evaluate(() => document.documentElement.scrollWidth)).toBe(390);
});
