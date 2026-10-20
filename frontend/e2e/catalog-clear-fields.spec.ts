import { test, expect } from "./fixtures";

test("advanced search clears each token field including drafts and resets its operator", async ({
  page,
}) => {
  await page.route("**/api/catalog/suggestions/**", (r) =>
    r.fulfill({ json: { items: [], total: 0, nextOffset: null } }),
  );
  await page.route("**/api/catalog/books?**", (r) =>
    r.fulfill({
      json: {
        items: [],
        meta: {
          page: 0,
          size: 20,
          totalItems: 0,
          totalPages: 0,
          hasNext: false,
          hasPrevious: false,
        },
      },
    }),
  );
  await page.goto("/catalog");
  await page.locator(".catalog-search summary").click();
  for (const label of [
    "Título",
    "Autor",
    "Colección",
    "Género",
    "EPL ID",
    "Revisión",
  ]) {
    const input = page
      .getByRole("textbox", { name: label, exact: true })
      .first();
    await input.fill(
      label === "EPL ID" ? "1" : label === "Revisión" ? "1.4" : "First value",
    );
    await input.press("Enter");
    const wrapper = page
      .locator(".mantine-InputWrapper-root")
      .filter({ has: input });
    const operator = wrapper.getByRole("button", {
      name:
        label === "EPL ID" || label === "Revisión"
          ? "Incluir valores seleccionados"
          : "Contiene alguno de los textos indicados",
      exact: true,
    });
    await operator.click();
    await input.fill(
      label === "EPL ID"
        ? "2"
        : label === "Revisión"
          ? "2.3"
          : "Uncommitted draft",
    );
    await page
      .getByRole("button", { name: `Limpiar: ${label}`, exact: true })
      .click();
    await expect(input).toHaveValue("");
    await expect(wrapper.locator(".mantine-Pill-root")).toHaveCount(0);
    await expect(operator).toHaveText("=");
    await expect(
      page.getByRole("button", { name: `Limpiar: ${label}`, exact: true }),
    ).toHaveCount(0);
  }
  const input = page.getByRole("textbox", { name: "EPL ID", exact: true });
  await input.fill("wrong");
  await input.press("Enter");
  await expect(
    page.getByText("Introduce un valor válido.", { exact: true }),
  ).toBeVisible();
  await page
    .getByRole("button", { name: "Limpiar: EPL ID", exact: true })
    .click();
  await expect(
    page.getByText("Introduce un valor válido.", { exact: true }),
  ).not.toBeVisible();
  await page.getByRole("button", { name: "Buscar", exact: true }).click();
  expect(new URL(page.url()).searchParams.get("eplId")).toBeNull();
});

test("numeric ranges have independent internal clear buttons and unlink equal bounds", async ({
  page,
}) => {
  const queries: URLSearchParams[] = [];
  await page.route("**/api/catalog/books?**", (r) => {
    queries.push(new URL(r.request().url()).searchParams);
    return r.fulfill({
      json: {
        items: [],
        meta: {
          page: 0,
          size: 20,
          totalItems: 0,
          totalPages: 0,
          hasNext: false,
          hasPrevious: false,
        },
      },
    });
  });
  await page.goto("/catalog");
  await page.locator(".catalog-search summary").click();
  for (const [label, from, to, value] of [
    ["Páginas", "Mínimo", "Máximo", "10"],
    ["Año de publicación", "Desde", "Hasta", "2020"],
  ]) {
    const range = page
      .locator("fieldset")
      .filter({ has: page.locator("legend", { hasText: label }) });
    const lower = range.getByLabel(from, { exact: true }),
      upper = range.getByLabel(to, { exact: true });
    await expect(
      range.getByRole("button", { name: "Limpiar intervalo", exact: true }),
    ).toHaveCount(0);
    await lower.fill(value);
    await range
      .getByRole("button", {
        name:
          label === "Páginas" ? "Igualar mínimo y máximo" : "Igualar fechas",
        exact: true,
      })
      .click();
    await expect(upper).toHaveValue(value);
    await range
      .getByRole("button", { name: `Limpiar: ${label} · ${from}`, exact: true })
      .click();
    await expect(lower).toHaveValue("");
    await expect(upper).toHaveValue(value);
    await expect(
      range.getByRole("button", {
        name:
          label === "Páginas" ? "Igualar mínimo y máximo" : "Igualar fechas",
        exact: true,
      }),
    ).toHaveAttribute("aria-pressed", "false");
    await range
      .getByRole("button", { name: `Limpiar: ${label} · ${to}`, exact: true })
      .click();
    await expect(upper).toHaveValue("");
    await upper.fill(value);
  }
  await page.getByRole("button", { name: "Buscar", exact: true }).click();
  await expect.poll(() => queries.at(-1)?.get("pagesTo")).toBe("10");
  expect(queries.at(-1)?.has("pagesFrom")).toBe(false);
  expect(queries.at(-1)?.get("publicationYearTo")).toBe("2020");
  expect(queries.at(-1)?.has("publicationYearFrom")).toBe(false);
});

test("select and date fields clear independently and the layout fits mobile", async ({
  page,
}) => {
  await page.route("**/api/catalog/books?**", (r) =>
    r.fulfill({
      json: {
        items: [],
        meta: {
          page: 0,
          size: 20,
          totalItems: 0,
          totalPages: 0,
          hasNext: false,
          hasPrevious: false,
        },
      },
    }),
  );
  await page.goto("/catalog");
  await page.locator(".catalog-search summary").click();
  for (const [label, option] of [
    ["Idioma", "Español"],
    ["Estado", "Verificado"],
    ["Estado de publicación", "Actualizado"],
  ]) {
    const input = page
      .getByRole("textbox", { name: label, exact: true })
      .first();
    await input.click();
    await page.getByRole("option", { name: option, exact: true }).click();
    await page.keyboard.press("Escape");
    await page
      .getByRole("button", { name: `Limpiar: ${label}`, exact: true })
      .click();
    await expect(
      page
        .locator(".mantine-InputWrapper-root")
        .filter({ has: input })
        .locator(".mantine-Pill-root"),
    ).toHaveCount(0);
  }
  for (const label of ["Publicado en EPL", "Añadido a EPL Sync"]) {
    const range = page
      .locator("fieldset")
      .filter({ has: page.locator("legend", { hasText: label }) });
    await range.getByLabel("Desde", { exact: true }).fill("2026-01-01");
    await range.getByLabel("Hasta", { exact: true }).fill("2026-02-01");
    await range
      .getByRole("button", { name: `Limpiar: ${label} · Desde`, exact: true })
      .click();
    await expect(range.getByLabel("Desde", { exact: true })).toHaveValue("");
    await expect(range.getByLabel("Hasta", { exact: true })).toHaveValue(
      "2026-02-01",
    );
  }
  await page.getByLabel("Mínimo", { exact: true }).fill("100");
  await page.getByLabel("Máximo", { exact: true }).fill("200");
  await page
    .locator(".catalog-search")
    .screenshot({ path: "/tmp/eplsync-catalog-clear-desktop.png" });
  await page.setViewportSize({ width: 390, height: 844 });
  expect(
    await page.evaluate(
      () => document.documentElement.scrollWidth <= window.innerWidth + 1,
    ),
  ).toBe(true);
  await page
    .locator(".catalog-search")
    .screenshot({ path: "/tmp/eplsync-catalog-clear-mobile.png" });
});
