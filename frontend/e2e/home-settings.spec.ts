import { test, expect } from "./fixtures";

test("home preferences support ordering, per-shelf counts, persistence and header dismissal", async ({
  page,
}) => {
  await page.route("**/api/catalog/books?**", (route) => {
    const params = new URL(route.request().url()).searchParams;
    const count = Number(params.get("size"));
    return route.fulfill({
      json: {
        items: Array.from({ length: count }, (_, i) => ({
          eplId: i + 1,
          title: `Libro ${i + 1}`,
          author: "Autora",
          coverUrl: "invalid",
        })),
        meta: { totalItems: 200 },
      },
    });
  });
  await page.route("**/api/torrent/downloads/summary", (r) =>
    r.fulfill({ json: { total: 0, byStatus: {} } }),
  );
  await page.route("**/api/torrent/jobs?**", (r) =>
    r.fulfill({ json: { items: [], meta: { totalItems: 0 } } }),
  );
  await page.route("**/api/events/operations?**", (r) => {
    const count = Number(new URL(r.request().url()).searchParams.get("size"));
    return r.fulfill({
      json: {
        items: Array.from({ length: count }, (_, index) => ({
          latest: {
            operationId: `event-${index}`,
            action: "UPDATE",
            outcome: "SUCCEEDED",
            createdAt: "2026-10-06T12:00:00Z",
          },
        })),
        total: 100,
      },
    });
  });
  await page.goto("/settings/home");
  const rows = page.locator(".home-section-settings > li");
  await expect(rows).toHaveCount(6);
  await expect(
    page.getByRole("textbox", { name: "Número de eventos" }),
  ).toHaveValue("10");
  await page.getByRole("textbox", { name: "Número de eventos" }).fill("100");
  await expect(
    page.locator(".home-section-settings .mantine-InputWrapper-required"),
  ).toHaveCount(0);
  const listBox = (await page.locator(".home-section-settings").boundingBox())!;
  const panelBox = (await page
    .locator(".panel.settings-section")
    .boundingBox())!;
  expect(listBox.width).toBeLessThanOrEqual(760);
  expect(listBox.width).toBeLessThan(panelBox.width);

  const heights = await rows.evaluateAll((elements) =>
    elements.map((element) => element.getBoundingClientRect().height),
  );
  expect(Math.max(...heights) - Math.min(...heights)).toBeLessThan(1);
  const countField = rows
    .nth(2)
    .getByRole("textbox", { name: "Número de libros" });
  const countLabel = rows.nth(2).locator(".mantine-NumberInput-label");
  const inputBox = (await countField.boundingBox())!;
  const labelBox = (await countLabel.boundingBox())!;
  expect(labelBox.x + labelBox.width).toBeLessThan(inputBox.x);
  expect(
    Math.abs(
      labelBox.y + labelBox.height / 2 - inputBox.y - inputBox.height / 2,
    ),
  ).toBeLessThan(2);

  const newReleases = rows.filter({
    has: page.getByRole("checkbox", { name: "Novedades", exact: true }),
  });
  await newReleases
    .getByRole("textbox", { name: "Número de libros" })
    .fill("100");
  await page
    .getByRole("button", { name: "Subir Novedades", exact: true })
    .click();
  await expect(rows.nth(1).getByRole("checkbox")).toHaveAccessibleName(
    "Novedades",
  );
  await rows.nth(1).locator(".home-section-grip").dragTo(rows.nth(0));
  await expect(rows.nth(0).getByRole("checkbox")).toHaveAccessibleName(
    "Novedades",
  );
  await page.getByRole("button", { name: "Guardar", exact: true }).click();
  await expect(page.getByRole("status")).toHaveText("Cambios guardados.");
  await page.reload();
  await expect(rows.nth(0).getByRole("checkbox")).toHaveAccessibleName(
    "Novedades",
  );
  await expect(
    rows.nth(0).getByRole("textbox", { name: "Número de libros" }),
  ).toHaveValue("100");
  await page.goto("/");
  await expect(page.locator(".home-events li")).toHaveCount(100);
  const shelf = page.locator(".home-panel").filter({
    has: page.getByRole("heading", { name: "Novedades", exact: true }),
  });
  await expect(shelf.locator(".home-book")).toHaveCount(100);
  expect(
    await shelf
      .locator(".home-books")
      .evaluate((element) => element.scrollWidth > element.clientWidth),
  ).toBe(true);
  await page
    .getByRole("button", { name: "Ocultar presentación", exact: true })
    .click();
  await expect(page.locator(".home-hero")).toHaveCount(0);
  await page.goto("/settings/home");
  await expect(
    page.getByRole("checkbox", { name: "Encabezado", exact: true }),
  ).not.toBeChecked();
  await page
    .getByRole("button", { name: "Restaurar predeterminados", exact: true })
    .click();
  await expect(
    page.getByRole("checkbox", { name: "Encabezado", exact: true }),
  ).toBeChecked();
  await expect(
    page.getByRole("checkbox", {
      name: "Últimos libros añadidos",
      exact: true,
    }),
  ).toBeChecked();
  await page.getByRole("button", { name: "Guardar", exact: true }).click();
  await expect(page.getByRole("status")).toHaveText("Cambios guardados.");
  await expect(
    page.getByRole("textbox", { name: "Número de eventos" }),
  ).toHaveValue("10");
  await page.setViewportSize({ width: 390, height: 844 });
  await page.waitForTimeout(400); // Wait for the sidebar resize transition.
  const mobileHeights = await rows.evaluateAll((elements) =>
    elements.map((element) => element.getBoundingClientRect().height),
  );
  expect(Math.max(...mobileHeights) - Math.min(...mobileHeights)).toBeLessThan(
    1,
  );

  expect(
    await page.evaluate(
      () => document.documentElement.scrollWidth <= innerWidth,
    ),
  ).toBe(true);
  await page.screenshot({
    path: "/tmp/eplsync-home-settings-mobile.png",
    fullPage: true,
  });
  await page.setViewportSize({ width: 1440, height: 1000 });
  await page.screenshot({
    path: "/tmp/eplsync-home-settings-desktop.png",
    fullPage: true,
  });
});

test("home respects permissions even when all sections are enabled", async ({
  page,
}) => {
  await page.route("**/api/auth/me", (r) =>
    r.fulfill({
      json: {
        id: "reader",
        username: "reader",
        email: "reader@example.org",
        role: "USER",
        mustChangePassword: false,
        permissions: ["CATALOG_READ"],
      },
    }),
  );
  await page.route("**/api/catalog/books?**", (r) =>
    r.fulfill({ json: { items: [], meta: { totalItems: 0 } } }),
  );
  const requests: string[] = [];
  page.on("request", (r) => requests.push(r.url()));
  await page.goto("/");
  await expect(
    page.getByRole("heading", { name: "Novedades", exact: true }),
  ).toBeVisible();
  await expect(
    page.getByRole("heading", { name: "Últimos eventos", exact: true }),
  ).toHaveCount(0);
  expect(
    requests.some(
      (url) => url.includes("/torrent/") || url.includes("/events/operations"),
    ),
  ).toBe(false);
  await page.goto("/settings/home");
  await expect(
    page.getByRole("checkbox", { name: "Encabezado", exact: true }),
  ).toBeVisible();
});
