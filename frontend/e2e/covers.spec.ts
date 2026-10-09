import { test, expect } from "./fixtures";

const custom = "https://covers.example/book.jpg";
const fallback = "https://images.epublibre.org/libros/32.jpg";
const book = {
  eplId: 32,
  title: "Dune",
  author: "Frank Herbert",
  revision: 1,
  language: "es",
  download: { items: [] },
};

test("unavailable stored cover uses ePubLibre without a verification request", async ({
  page,
}) => {
  let storedRequests = 0;
  let checks = 0;
  await page.route(custom, (r) => {
    storedRequests++;
    return r.abort();
  });
  await page.route("**/api/catalog/covers/check**", (r) => {
    checks++;
    return r.abort();
  });
  await page.route("**/api/catalog/books/32", (r) =>
    r.fulfill({ json: { ...book, coverUrl: custom, coverAvailable: false } }),
  );
  await page.goto("/catalog/32");
  await expect(page.locator(".detail-cover img")).toHaveAttribute(
    "src",
    fallback,
  );
  await expect(page.locator(".detail-cover")).toHaveAttribute("href", fallback);
  expect(storedRequests).toBe(0);
  expect(checks).toBe(0);
});

test.beforeEach(async ({ page }) => {
  await page.route("**/api/catalog/covers/task", (r) =>
    r.fulfill({ json: { task: null } }),
  );
  await page.route("**/api/ui/config", (r) =>
    r.fulfill({ json: { defaultLanguage: "es" } }),
  );
  await page.route("**/api/catalog/books/32/magnets", (r) =>
    r.fulfill({ json: [] }),
  );
  // Local fixture: tests never depend on remote image servers.
  for (const url of [custom, fallback]) {
    await page.route(url, (r) =>
      r.fulfill({
        contentType: "image/svg+xml",
        body: '<svg xmlns="http://www.w3.org/2000/svg" width="130" height="175"><rect width="130" height="175" fill="teal"/></svg>',
      }),
    );
  }
});

for (const coverUrl of [custom, null]) {
  test(`table and detail use ${coverUrl ? "stored cover" : "ePubLibre fallback"}`, async ({
    page,
  }) => {
    const expected = coverUrl || fallback;
    await page.route("**/api/catalog/books?*", (r) =>
      r.fulfill({
        json: {
          items: [{ ...book, coverUrl }],
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
    await page.route("**/api/catalog/books/32", (r) =>
      r.fulfill({ json: { ...book, coverUrl } }),
    );
    await page.goto("/catalog");
    const thumbnail = page.locator(".mini-book img");
    await expect(thumbnail).toHaveAttribute("src", expected);
    await expect(thumbnail).toHaveAttribute("loading", "lazy");
    await expect(page.locator(".mini-book")).toHaveCSS("width", "44px");
    await page.getByRole("link", { name: "Dune", exact: true }).click();
    const cover = page.getByRole("link", {
      name: "Abrir portada de Dune en una nueva pestaña",
    });
    await expect(cover).toHaveAttribute("href", expected);
    await expect(cover).toHaveAttribute("target", "_blank");
    await expect(cover.locator("img")).toHaveCSS("border-radius", "5px");
    await expect(cover.locator("img")).toHaveCSS("height", "300px");
    await expect(cover.locator("img")).toHaveAttribute("src", expected);
    await expect
      .poll(() =>
        cover
          .locator("img")
          .evaluate((img: HTMLImageElement) => img.naturalWidth),
      )
      .toBeGreaterThan(0);
    await page.setViewportSize({ width: 390, height: 844 });
    await expect(cover.locator("img")).toHaveCSS("object-fit", "contain");
    await expect(cover.locator("img")).toHaveCSS("height", "165px");
  });
}

test("failed stored cover keeps placeholder without requesting the fallback", async ({
  page,
}) => {
  let fallbackRequests = 0;
  await page.route(fallback, (r) => {
    fallbackRequests++;
    return r.abort();
  });
  await page.route(custom, (r) => r.fulfill({ status: 404 }));
  await page.route("**/api/catalog/books/32", (r) =>
    r.fulfill({ json: { ...book, coverUrl: custom } }),
  );
  await page.goto("/catalog/32");
  await expect(page.locator(".detail-cover span")).toHaveText("EPL 32");
  await expect(page.locator(".detail-cover img")).toHaveCount(0);
  expect(fallbackRequests).toBe(0);
});

test("non-http stored URLs cannot become executable links", async ({
  page,
}) => {
  await page.route("**/api/catalog/books/32", (r) =>
    r.fulfill({ json: { ...book, coverUrl: "javascript:alert(1)" } }),
  );
  await page.goto("/catalog/32");
  await expect(page.locator(".detail-cover span")).toHaveText("EPL 32");
  await expect(page.locator("a.detail-cover")).toHaveCount(0);
});
