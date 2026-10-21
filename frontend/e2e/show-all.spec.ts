import { test, expect } from "./fixtures";
test("show all stays bounded and restores the previous numeric size when filters exceed the limit", async ({
  page,
}) => {
  let total = 1000;
  const requests: URLSearchParams[] = [];
  await page.route("**/api/torrent/downloads?**", (r) => {
    const params = new URL(r.request().url()).searchParams;
    requests.push(params);
    const size = Number(params.get("size"));
    const current = Number(params.get("page"));
    return r.fulfill({
      json: {
        items: [
          {
            id: "one",
            eplId: 23,
            title: "Un libro",
            hash: "A".repeat(40),
            revision: 1,
            client: "qbittorrent",
            origin: "EPLSYNC",
            status: "SUBMITTED",
          },
        ],
        meta: {
          page: current,
          size,
          totalItems: total,
          totalPages: Math.ceil(total / size),
          hasNext: (current + 1) * size < total,
          hasPrevious: current > 0,
        },
      },
    });
  });
  await page.goto("/downloads");
  const size = page.getByRole("textbox", {
    name: "Elementos por página",
    exact: true,
  });
  await size.click();
  await page.getByRole("option", { name: "50", exact: true }).click();
  await expect(size).toHaveValue("50");
  await size.click();
  await page
    .getByRole("option", { name: "Mostrar todos", exact: true })
    .click();
  await expect(size).toHaveValue("Mostrar todos");
  await expect.poll(() => requests.at(-1)?.get("size")).toBe("1000");
  total = 1001;
  await page.getByRole("textbox", { name: "EPL ID", exact: true }).fill("23");
  await page.getByRole("textbox", { name: "EPL ID", exact: true }).press("Enter");
  await expect(size).toHaveValue("50");
  await expect.poll(() => requests.at(-1)?.get("size")).toBe("50");
  expect(requests.at(-1)?.get("page")).toBe("0");
  await size.click();
  await expect(
    page.getByRole("option", { name: "Mostrar todos", exact: true }),
  ).toHaveCount(0);
  await page.getByRole("option", { name: "1000", exact: true }).click();
  await expect(size).toHaveValue("1000");
  expect(requests.every((p) => Number(p.get("size")) <= 1000)).toBe(true);
});

test("catalog preserves show-all across loading and displays every filtered record", async ({
  page,
}) => {
  const total = 25;
  await page.route("**/api/catalog/books?**", async (r) => {
    const params = new URL(r.request().url()).searchParams;
    const size = Number(params.get("size"));
    const current = Number(params.get("page"));
    await new Promise((resolve) => setTimeout(resolve, 100));
    await r.fulfill({
      json: {
        items: Array.from({ length: Math.min(size, total) }, (_, i) => ({
          eplId: i + 1,
          title: "Libro " + (i + 1),
          author: "Autor",
          revision: 1,
          download: { items: [] },
        })),
        meta: {
          page: current,
          size,
          totalItems: total,
          totalPages: Math.ceil(total / size),
          hasNext: size < total,
          hasPrevious: current > 0,
        },
      },
    });
  });
  await page.goto("/catalog?size=10");
  await expect(page.locator(".catalog-table tbody tr")).toHaveCount(10);
  const size = page.getByRole("textbox", {
    name: "Libros por página",
    exact: true,
  });
  await size.click();
  await page
    .getByRole("option", { name: "Mostrar todos", exact: true })
    .click();
  await expect(size).toHaveValue("Mostrar todos");
  await expect(page.locator(".catalog-table tbody tr")).toHaveCount(total);
  await expect(page).toHaveURL(/size=1000/);
  await expect(
    page.getByRole("spinbutton", { name: "Página", exact: true }),
  ).toHaveValue("1");
});
