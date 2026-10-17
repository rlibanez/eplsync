import { test, expect } from "./fixtures";

test.beforeEach(async ({ page }) => {
  await page.route("**/api/catalog/books?**", route => route.fulfill({json: {
    items: [], meta: {page: 0, size: 20, totalItems: 0, totalPages: 0, hasNext: false, hasPrevious: false},
  }}));
});

test("EPL ID lists validate atomically and submit repeated ID parameters", async ({ page }) => {
  await page.goto("/catalog");
  const search = page.locator(".catalog-search");
  await search.locator("summary").click();
  const ids = search.getByRole("textbox", {name: "EPL ID", exact: true});
  await ids.fill("123, bad; 456");
  await ids.press("Enter");
  await expect(search.getByText("Introduce un valor válido.")).toBeVisible();
  await expect(search.getByRole("button", {name: "Quitar 123", exact: true})).toHaveCount(0);
  await ids.fill("123,456; 789 123");
  await search.getByRole("button", {name: "Buscar", exact: true}).click();
  await expect(page).toHaveURL(/eplId=123&eplId=456&eplId=789/);
});

test("pasted multiline IDs remain separate and number ranges use one grid column", async ({ page }) => {
  await page.goto("/catalog");
  const search = page.locator(".catalog-search");
  await search.locator("summary").click();
  const ids = search.getByRole("textbox", {name: "EPL ID", exact: true});
  await ids.focus();
  await ids.evaluate(node => {
    const clipboard = new DataTransfer();
    clipboard.setData("text", "123\n456\r\n789");
    node.dispatchEvent(new ClipboardEvent("paste", {clipboardData: clipboard, bubbles: true, cancelable: true}));
  });
  await expect(ids).toHaveValue("123 456 789");
  const first = await search.locator(".catalog-filter-grid > :first-child").first().boundingBox();
  for (const label of ["Páginas", "Año de publicación", "Publicado en EPL", "Añadido a EPL Sync"]) {
    const range = search.getByRole("group", {name: label, exact: true});
    const box = await range.boundingBox();
    expect(Math.abs(box!.width - first!.width)).toBeLessThan(2);
    const revision = await search.locator(".catalog-filter-grid").first().getByRole("textbox", {name: "Revisión", exact: true}).boundingBox();
    expect(box!.y).toBeGreaterThan(revision!.y + revision!.height);
  }
  await ids.press("Enter");
  await search.getByRole("button", {name: "Buscar", exact: true}).click();
  await expect(page).toHaveURL(/eplId=123&eplId=456&eplId=789/);
});

test("more than 100 EPL IDs are submitted without a client limit", async ({
  page,
}) => {
  const values = Array.from({ length: 150 }, (_, i) => String(i + 1));
  await page.goto("/catalog");
  const search = page.locator(".catalog-search");
  await search.locator("summary").click();
  await search
    .getByRole("textbox", { name: "EPL ID", exact: true })
    .fill(values.join(" "));
  const request = page.waitForRequest(
    (r) =>
      r.url().includes("/api/catalog/books?") &&
      new URL(r.url()).searchParams.getAll("eplId").length === 150,
  );
  await search.getByRole("button", { name: "Buscar", exact: true }).click();
  expect(new URL((await request).url()).searchParams.getAll("eplId")).toEqual(
    values,
  );
});
for (const status of [400, 403, 404, 413, 414, 422, 431, 503]) {
  test(`catalog HTTP ${status} offers retry only for temporary failures`, async ({
    page,
  }) => {
    await page.goto("/catalog");
    await page.locator(".catalog-search summary").click();
    let calls = 0;
    await page.route("**/api/catalog/books?**", (r) => {
      calls++;
      return status === 400 || status === 422
        ? r.fulfill({
            status,
            json: { details: "Revisa los EPL ID indicados" },
          })
        : r.fulfill({
            status,
            contentType: "text/html",
            body: "<html>Request rejected</html>",
          });
    });
    await page
      .locator(".catalog-search")
      .getByRole("textbox", { name: "EPL ID", exact: true })
      .fill("1");
    await page
      .locator(".catalog-search")
      .getByRole("button", { name: "Buscar", exact: true })
      .click();
    const alert = page.getByRole("alert").filter({
      hasText:
        status === 400 || status === 422
          ? "Revisa los EPL ID indicados"
          : [413, 414, 431].includes(status)
            ? "grupos más pequeños"
            : status === 404
              ? "No se ha encontrado"
              : `HTTP ${status}`,
    });
    await expect(alert).toBeVisible();
    if (status === 503)
      await expect(
        alert.getByRole("button", { name: "Reintentar", exact: true }),
      ).toBeVisible();
    else {
      await expect(
        alert.getByRole("button", { name: "Reintentar", exact: true }),
      ).toHaveCount(0);
      expect(calls).toBe(1);
    }
    if ([413, 414, 431].includes(status))
      await expect(alert).toContainText("grupos más pequeños");
    if ([400, 422].includes(status))
      await expect(alert).toContainText("Revisa los EPL ID indicados");
  });
}
