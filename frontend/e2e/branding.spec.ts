import { test, expect } from "./fixtures";

const palettes = [
  ["teal", 150],
  ["blue", 215],
  ["violet", 265],
  ["rose", 335],
  ["orange", 30],
  ["cyan", 185],
  ["indigo", 240],
  ["grape", 285],
  ["lime", 85],
  ["yellow", 48],
] as const;

test("logo and favicon follow all palettes and both schemes without painted counters", async ({
  page,
}) => {
  await page.goto("/settings/general");
  const logo = page.locator(".brand-icon svg");
  await expect(logo).toBeVisible();
  // Cat and three empty book outlines: no interior lines in the default logo.
  await expect(logo.locator("path")).toHaveCount(4);
  await expect(logo).toHaveAttribute("aria-hidden", "true");
  await expect(logo.locator('path[fill="white"], mask, image')).toHaveCount(0);
  const originalShape = await logo.innerHTML();
  for (const scheme of ["light", "dark"] as const) {
    await page
      .getByRole("radio", {
        name: scheme === "light" ? "Claro" : "Oscuro",
        exact: true,
      })
      .check();
    for (const [palette, hue] of palettes) {
      await page.locator(`input[name="palette"][value="${palette}"]`).check();
      await expect(page.locator("html")).toHaveAttribute(
        "data-palette",
        palette,
      );
      const expected = `hsl(${hue} ${scheme === "light" ? "55% 29%" : "40% 76%"})`;
      await expect
        .poll(() =>
          logo.evaluate((el) =>
            getComputedStyle(el).getPropertyValue("--accent").trim(),
          ),
        )
        .toBe(expected);
      const foreground = await logo.evaluate(
        (el) => getComputedStyle(el).color,
      );
      expect(
        await logo
          .locator("path")
          .first()
          .evaluate((el) => getComputedStyle(el).fill),
      ).toBe(foreground);
      expect(await logo.innerHTML()).toBe(originalShape);
      await expect
        .poll(async () => {
          const href = await page
            .locator('link[rel="icon"]')
            .getAttribute("href");
          return href?.startsWith("data:")
            ? decodeURIComponent(href.split(",")[1])
            : "";
        })
        .toContain(`fill="${expected}"`);
      if (palette === "teal")
        await page.screenshot({ path: `test-results/branding-${scheme}.png` });
    }
  }
  await page.reload();
  await expect(page.locator("html")).toHaveAttribute("data-palette", "yellow");
  await expect(page.locator("html")).toHaveAttribute(
    "data-mantine-color-scheme",
    "dark",
  );
  await page.setViewportSize({ width: 390, height: 844 });
  await expect(page.locator(".mobile-brand svg")).toBeVisible();
  await expect(page.locator(".mobile-brand")).toHaveAccessibleName("EPL Sync");
  await page.screenshot({ path: "test-results/branding-mobile.png" });
});
