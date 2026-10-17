import { test, expect } from "./fixtures";

test("download book filter excludes, paginates, selects all and clears", async ({
  page,
}) => {
  const requests: URLSearchParams[] = [];
  await page.route("**/api/torrent/downloads?**", (r) => {
    const p = new URL(r.request().url()).searchParams;
    requests.push(p);
    const size = Number(p.get("size") ?? 20);
    return r.fulfill({
      json: {
        items: Array.from({ length: size === 1000 ? 40 : 20 }, (_, i) => ({
          id: String(i + Number(p.get("page") ?? 0) * size),
          eplId: i + 1,
          title: "Other",
          status: "DOWNLOADED",
          origin: "EPLSYNC",
          revision: 1,
        })),
        meta: {
          page: Number(p.get("page")),
          size,
          totalItems: 40,
          totalPages: size === 1000 ? 1 : 2,
          hasNext: p.get("page") === "0" && size !== 1000,
          hasPrevious: p.get("page") === "1",
        },
      },
    });
  });
  await page.goto("/downloads");
  const field = page.getByRole("textbox", { name: "Libro", exact: true });
  const wrapper = page
    .locator(".mantine-InputWrapper-root")
    .filter({ has: field });
  await field.fill("Dune");
  await expect.poll(() => requests.at(-1)?.get("title")).toBe("Dune");
  await wrapper
    .getByRole("button", {
      name: "Contiene alguno de los textos indicados",
      exact: true,
    })
    .click();
  await expect.poll(() => requests.at(-1)?.get("titleOperator")).toBe("ne");
  await page.getByRole("button", { name: "Siguiente", exact: true }).click();
  await expect.poll(() => requests.at(-1)?.get("page")).toBe("1");
  expect(requests.at(-1)?.get("titleOperator")).toBe("ne");
  await page
    .getByRole("button", { name: "Seleccionar todo (40)", exact: true })
    .click();
  await expect(
    page.getByRole("button", { name: "Quitar selección (40)", exact: true }),
  ).toBeVisible();
  expect(
    requests
      .filter((p) => p.get("size") === "1000")
      .every(
        (p) => p.get("title") === "Dune" && p.get("titleOperator") === "ne",
      ),
  ).toBe(true);
  await page
    .getByRole("button", { name: "Limpiar: Libro", exact: true })
    .click();
  await expect(field).toHaveValue("");
  await page.getByRole("button", { name: "Siguiente", exact: true }).click();
  await expect.poll(() => requests.at(-1)?.has("title")).toBe(false);
  await expect(
    wrapper.getByRole("button", {
      name: "Contiene alguno de los textos indicados",
      exact: true,
    }),
  ).toHaveText("=");
  await field.fill("Other");
  await page.getByRole("button", { name: "Limpiar", exact: true }).click();
  await expect(field).toHaveValue("");
});
