import { test, expect } from "./fixtures";

for (const source of ["downloads", "calibre"] as const) {
  test(`${source} cleanup shows author after book and filters the complete job`, async ({
    page,
  }) => {
    const requests: URLSearchParams[] = [];
    await page.route("**/api/revision-cleanups/author-job", (route) =>
      route.fulfill({
        json: {
          id: "author-job",
          source,
          action: source === "calibre" ? "BOOK" : "FILES",
          state: "COMPLETED",
          result: "SUCCEEDED",
          directRemoval: true,
          total: 40,
          selected: 40,
          succeeded: 40,
          pending: 0,
          blocked: 0,
          failed: 0,
          uncertain: 0,
        },
      }),
    );
    await page.route(
      "**/api/revision-cleanups/author-job/items?**",
      (route) => {
        const params = new URL(route.request().url()).searchParams;
        requests.push(params);
        const p = Number(params.get("page") ?? 0);
        return route.fulfill({
          json: {
            items: [
              {
                id: "item",
                eplId: 1,
                title: "Libro",
                author: "Jane Austen",
                revision: "1",
                oldId: "old",
                replacementId: "",
                replacementRevision: "",
                formats: [],
                state: "SUCCEEDED",
                message: null,
              },
            ],
            meta: {
              page: p,
              size: 20,
              totalItems: 40,
              totalPages: 2,
              hasNext: p === 0,
              hasPrevious: p > 0,
            },
          },
        });
      },
    );
    await page.goto(`/${source}/jobs/cleanup/author-job`);
    const author = page.getByRole("textbox", { name: "Autor", exact: true });
    await expect(author).toBeVisible();
    const labels = await page.locator(".jobs-filters label").allTextContents();
    expect(labels.indexOf("Autor")).toBe(labels.indexOf("Libro") + 1);
    const headers = await page.getByRole("columnheader").allTextContents();
    expect(headers.indexOf("Autor")).toBe(headers.indexOf("Libro") + 1);
    await expect(
      page.getByRole("cell", { name: "Jane Austen", exact: true }),
    ).toBeVisible();
    for (const text of ["Jane", "George"]) {
      await author.fill(text);
      await author.press("Enter");
    }
    await expect
      .poll(() => requests.at(-1)?.getAll("author"))
      .toEqual(["Jane", "George"]);
    await page
      .locator(".jobs-filters > div")
      .filter({ has: author })
      .getByRole("button", {
        name: "Incluir valores seleccionados",
        exact: true,
      })
      .click();
    await page.getByRole("button", { name: "Siguiente", exact: true }).click();
    await expect.poll(() => requests.at(-1)?.get("page")).toBe("1");
    expect(requests.at(-1)?.get("authorOperator")).toBe("ne");
    await page
      .getByRole("button", { name: "Jane Austen", exact: true })
      .click();
    await expect
      .poll(() => requests.at(-1)?.getAll("author"))
      .toEqual(["Jane Austen"]);
    expect(requests.at(-1)?.get("page")).toBe("0");
    expect(requests.at(-1)?.has("authorOperator")).toBe(false);

    await page
      .getByRole("button", { name: "Limpiar: Autor", exact: true })
      .click();
    await expect(page.locator(".jobs-filters .mantine-Pill-label")).toHaveCount(
      0,
    );
    await page.getByRole("textbox", { name: "Estado", exact: true }).click();
    await page.getByRole("option", { name: "Eliminado", exact: true }).click();
    await page.keyboard.press("Escape");
    await expect.poll(() => requests.at(-1)?.get("state")).toBe("SUCCEEDED");
    expect(requests.at(-1)?.get("page")).toBe("0");
    expect(requests.at(-1)?.getAll("author")).toEqual([]);
    expect(requests.at(-1)?.has("authorOperator")).toBe(false);
  });
}
