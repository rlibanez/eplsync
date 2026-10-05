import { test, expect } from "./fixtures";

test("settings fields adapt to panel width and single fields share their size", async ({
  page,
}) => {
  await page.route("**/api/ui/config", (route) =>
    route.fulfill({ json: { defaultLanguage: "es" } }),
  );
  await page.route("**/api/catalog/covers/config", (route) =>
    route.fulfill({
      json: {
        connectTimeoutMs: 3000,
        requestTimeoutMs: 3000,
        batchTimeoutMs: 4000,
        concurrency: 4,
      },
    }),
  );
  await page.route("**/api/settings/covers", (route) =>
    route.fulfill({ json: { section: "covers", fields: [] } }),
  );
  await page.route("**/api/auth/status", (route) =>
    route.fulfill({ json: { initialized: true, passwordMinimumLength: 8 } }),
  );
  await page.goto("/settings/covers");
  const grid = page.locator(".cover-parameters .server-settings-grid").first();
  await expect(grid.locator("input")).toHaveCount(4);
  for (const width of [2560, 1440, 1000, 390]) {
    await page.setViewportSize({ width, height: 1000 });
    await expect
      .poll(async () =>
        grid.evaluate((element) => {
          const fields = [...element.querySelectorAll("input")].map((input) =>
            input.getBoundingClientRect(),
          );
          return fields.every(
            (field) =>
              field.width > 0 &&
              field.width <=
                (window.innerWidth <= 700 ? element.clientWidth : 360) &&
              field.right <= element.getBoundingClientRect().right + 1,
          );
        }),
      )
      .toBe(true);
    const columns = await grid.evaluate(
      (element) =>
        getComputedStyle(element).gridTemplateColumns.split(" ").length,
    );
    const expected = await grid.evaluate((element) =>
      Math.max(1, Math.floor((element.clientWidth + 20) / 320)),
    );
    expect(columns).toBe(expected);
    if (width === 2560)
      await grid.screenshot({ path: "/tmp/eplsync-settings-fields-wide.png" });
    if (width === 390) {
      await expect
        .poll(async () =>
          page
            .locator(".sidebar")
            .evaluate((element) => element.getBoundingClientRect().right),
        )
        .toBeLessThanOrEqual(0);
      await grid.screenshot({
        path: "/tmp/eplsync-settings-fields-mobile.png",
      });
      expect(columns).toBe(1);
      const box = (await grid.boundingBox())!;
      expect(
        (await grid.locator("input").first().boundingBox())!.width,
      ).toBeCloseTo(box.width, 0);
    }
  }
  await page.setViewportSize({ width: 2560, height: 1400 });
  await page.goto("/settings/general");
  const language = page.getByRole("combobox", {
    name: "Idioma de la interfaz",
  });
  const languageWidth = (await language.boundingBox())!.width;
  expect(languageWidth).toBeLessThanOrEqual(360);
  await page.goto("/settings/account");
  const current = page.getByLabel(/^Contraseña actual/);
  const next = page.getByLabel(/^Nueva contraseña/);
  // PasswordInput measures its inner input, inside the two wrapper borders.
  expect(
    Math.abs((await current.boundingBox())!.width - languageWidth),
  ).toBeLessThanOrEqual(2);
  expect((await current.boundingBox())!.x).toBe((await next.boundingBox())!.x);
});
