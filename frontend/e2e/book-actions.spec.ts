import { test, expect } from "@playwright/test";
const hash = "a".repeat(40);
const magnet = `magnet:?xt=urn:btih:${hash}&dn=Dune&tr=udp%3A%2F%2Ftracker.example%3A80`;
const book = {
  eplId: 32,
  title: "Dune",
  author: "Frank Herbert",
  revision: 1,
  language: "es",
  genres: null,
  publicationYear: 1965,
  pages: 600,
  rating: null,
  votesCount: null,
  volume: null,
  collection: null,
  publicationDate: null,
  insertDate: null,
  lastModifiedDate: null,
  publicationStatus: "PUBLISHED",
  status: "DISPONIBLE",
  synopsis: "Arrakis",
  download: { items: [] },
};
test.beforeEach(async ({ context }) => {
  await context.route("**/api/ui/config", (r) =>
    r.fulfill({ json: { defaultLanguage: "es" } }),
  );
  await context.route("**/api/catalog/books/32", (r) =>
    r.fulfill({ json: book }),
  );
  await context.route("**/api/catalog/books/32/magnets", (r) =>
    r.fulfill({ json: [magnet] }),
  );
});
test("detail submits directly without options and exposes the backend magnet", async ({
  page,
}) => {
  let calls = 0;
  let release: () => void = () => {};
  await page.route("**/api/torrent/books/32", async (r) => {
    calls++;
    expect(r.request().method()).toBe("POST");
    expect(r.request().postData()).toBe(null);
    await new Promise<void>((resolve) => (release = resolve));
    return r.fulfill({
      status: 202,
      json: { eplId: 32, hash, client: "qbittorrent", status: "ACCEPTED" },
    });
  });
  await page.goto("/catalog/32");
  await expect(
    page.getByRole("link", { name: "Abrir magnet", exact: true }),
  ).toHaveAttribute("href", magnet);
  const send = page.getByRole("button", {
    name: "Enviar a descargar",
    exact: true,
  });
  await expect(send).toBeEnabled();
  await send.click();
  await expect(send).toBeDisabled();
  await expect.poll(() => calls).toBe(1);
  release();
  await expect(page.getByRole("alert")).toContainText("opciones del servidor");
  await expect(page).toHaveURL(/\/catalog\/32$/);
  await expect(page.getByRole("dialog")).toHaveCount(0);
  await page.screenshot({
    path: "test-results/book-direct-actions.png",
    fullPage: true,
  });
});
test("multiple hashes require a choice but inherit every other server option", async ({
  page,
}) => {
  const second = "b".repeat(40);
  await page.route("**/api/catalog/books/32/magnets", (r) =>
    r.fulfill({ json: [magnet, `magnet:?xt=urn:btih:${second}`] }),
  );
  await page.route("**/api/torrent/books/32", (r) => {
    expect(r.request().postDataJSON()).toEqual({ hash: second });
    return r.fulfill({
      json: {
        eplId: 32,
        hash: second,
        client: "qbittorrent",
        status: "ALREADY_EXISTS",
      },
    });
  });
  await page.goto("/catalog/32");
  const send = page.getByRole("button", {
    name: "Enviar a descargar",
    exact: true,
  });
  await expect(send).toBeDisabled();
  await page
    .getByRole("textbox", { name: "Torrent que se enviará", exact: true })
    .click();
  await page.getByRole("option", { name: second, exact: true }).click();
  await send.click();
  await expect(page.getByRole("alert")).toContainText("ya existe");
  await page.getByRole("button", { name: "Abrir magnet", exact: true }).click();
  await expect(page.getByRole("menuitem").first()).toHaveAttribute(
    "href",
    magnet,
  );
});
test("failed direct send explains uncertainty without retrying", async ({
  page,
}) => {
  let calls = 0;
  await page.route("**/api/torrent/books/32", (r) => {
    calls++;
    return r.abort();
  });
  await page.goto("/catalog/32");
  await page
    .getByRole("button", { name: "Enviar a descargar", exact: true })
    .click();
  await expect(page.getByRole("alert")).toContainText(
    "podría haberse ejecutado",
  );
  expect(calls).toBe(1);
});
test("table arrow opens the full detail in a new tab and title stays in the current tab", async ({
  page,
  context,
}) => {
  await context.route("**/api/catalog/books?**", (r) =>
    r.fulfill({
      json: {
        items: [book],
        meta: {
          page: 0,
          size: 20,
          totalItems: 1,
          totalPages: 1,
          hasNext: false,
          hasPrevious: false,
        },
      },
    }),
  );
  await page.goto("/catalog");
  const arrow = page.getByRole("link", {
    name: "Abrir «Dune» en una nueva pestaña",
    exact: true,
  });
  await expect(arrow).toHaveAttribute("target", "_blank");
  await expect(arrow).toHaveAttribute("rel", "noopener noreferrer");
  const opened = context.waitForEvent("page");
  await arrow.click();
  const detail = await opened;
  await expect(detail).toHaveURL(/\/catalog\/32$/);
  await expect(
    detail.getByRole("heading", { name: "Dune", exact: true }),
  ).toBeVisible();
  await expect(detail.locator("#sidebar")).toBeVisible();
  await expect(
    detail.getByRole("link", { name: "Volver al catálogo" }),
  ).toBeVisible();
  expect(await detail.evaluate(() => window.opener === null)).toBe(true);
  await expect(page).toHaveURL(/\/catalog$/);
  await detail.close();
  await page.getByRole("link", { name: "Dune EPL 32", exact: true }).click();
  await expect(page).toHaveURL(/\/catalog\/32$/);
});
