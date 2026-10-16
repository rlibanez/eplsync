import { test, expect } from "./fixtures";
const meta = (page: number, size: number) => ({
  page,
  size,
  totalItems: 40,
  totalPages: 2,
  hasNext: page === 0,
  hasPrevious: page > 0,
});
test("catalog row numbers follow pagination, reset after sorting and filtering, and can be hidden and moved", async ({
  page,
}) => {
  await page.route("**/api/catalog/books?**", (route) => {
    const p = new URL(route.request().url()).searchParams,
      current = Number(p.get("page") ?? 0),
      size = Number(p.get("size") ?? 20);
    const descending = p.get("sort") === "title,desc";
    return route.fulfill({
      json: {
        items: [1, 2].map((n) => ({
          eplId: 100 + n,
          title: `Book ${descending ? 3 - n : n}`,
          author: "Author",
          revision: 1,
          language: "es",
          download: { items: [] },
        })),
        meta: meta(current, size),
      },
    });
  });
  await page.goto("/catalog");
  const numbers = page.locator("tbody td.row-number");
  await expect(numbers).toHaveText(["1", "2"]);
  await page.getByRole("button", { name: "Siguiente", exact: true }).click();
  await expect(numbers).toHaveText(["21", "22"]);
  await page.getByRole("button", { name: "Libro", exact: true }).click();
  await expect(numbers).toHaveText(["1", "2"]);
  await expect(page.locator("tbody .book-title").first()).toHaveText("Book 2");
  await page.getByRole("button", { name: "Siguiente", exact: true }).click();
  await page
    .locator(".catalog-value-filter")
    .filter({ hasText: "Author" })
    .first()
    .click();
  await expect(numbers).toHaveText(["1", "2"]);
  await page.getByRole("button", { name: "Columnas", exact: true }).click();
  await page
    .getByRole("button", { name: "Mover # hacia abajo", exact: true })
    .click();
  await page.getByRole("checkbox", { name: "#", exact: true }).uncheck();
  await page.keyboard.press("Escape");
  await expect(numbers).toHaveCount(0);
  await page.reload();
  await expect(numbers).toHaveCount(0);
  await page.getByRole("button", { name: "Columnas", exact: true }).click();
  await page.getByRole("checkbox", { name: "#", exact: true }).check();
  await page.keyboard.press("Escape");
  await expect(numbers).toHaveText(["1", "2"]);
  const columns = await page
    .locator("thead th")
    .evaluateAll((elements) =>
      elements.map((e) => e.getAttribute("data-column")),
    );
  expect(columns.indexOf("rowNumber")).toBeGreaterThan(
    columns.indexOf("title"),
  );
  await expect(
    page.getByRole("button", { name: "#", exact: true }),
  ).toHaveCount(0);
});
test("shared download tables number visible rows independently of IDs and retain index visibility preferences", async ({
  page,
}) => {
  await page.route("**/api/torrent/downloads?**", (route) => {
    const p = new URL(route.request().url()).searchParams,
      current = Number(p.get("page") ?? 0),
      size = Number(p.get("size") ?? 20);
    return route.fulfill({
      json: {
        items: [99, 42].map((id) => ({
          id: String(id),
          eplId: id,
          title: `Book ${id}`,
          hash: "A".repeat(40),
          revision: 1,
          client: "qbittorrent",
          clientInstanceId: "test",
          origin: "EPLSYNC",
          status: "DOWNLOADED",
          lastCheckedAt: null,
          completedAt: null,
        })),
        meta: meta(current, size),
      },
    });
  });
  await page.goto("/downloads");
  const numbers = page.locator("tbody td.row-number");
  await expect(numbers).toHaveText(["1", "2"]);
  await page.getByRole("button", { name: "Siguiente", exact: true }).click();
  await expect(numbers).toHaveText(["21", "22"]);
  await page
    .locator('th[data-table-column="revision"] button.catalog-sort-heading')
    .click();
  await expect(numbers).toHaveText(["1", "2"]);
  await page.getByRole("button", { name: "Columnas", exact: true }).click();
  await page.getByRole("checkbox", { name: "#", exact: true }).uncheck();
  await page.keyboard.press("Escape");
  await expect(numbers).toHaveCount(0);
  await page.reload();
  await expect(numbers).toHaveCount(0);
});

test("catalog index fits its digits and ignores obsolete saved widths without truncation", async ({page}) => {
  await page.addInitScript(()=>localStorage.setItem("eplsync.catalog.columns.v1", JSON.stringify({order:["selection","rowNumber","title","author"],hidden:[],widths:{rowNumber:36,title:320,author:180}})));
  await page.route("**/api/catalog/books?**", route=>{
    const p=new URL(route.request().url()).searchParams,current=Number(p.get("page")??0),size=Number(p.get("size")??20);
    return route.fulfill({json:{items:[1,2].map(n=>({eplId:n,title:`Book ${n}`,author:"Author",revision:1,language:"es",download:{items:[]}})),meta:{page:current,size,totalItems:80000,totalPages:4000,hasNext:true,hasPrevious:current>0}}});
  });
  let smallWidth=0;
  for(const current of [0,296,2960]) {
    await page.goto(`/catalog?page=${current}`);
    const cell=page.locator("tbody td.row-number").first();
    await expect(cell).toHaveText(String(current*20+1));
    const geometry=await cell.evaluate(node=>({width:node.getBoundingClientRect().width,client:node.clientWidth,scroll:node.scrollWidth,ellipsis:getComputedStyle(node).textOverflow}));
    expect(geometry.scroll).toBeLessThanOrEqual(geometry.client);
    expect(geometry.ellipsis).not.toBe("ellipsis");
    if(current===0){smallWidth=geometry.width;expect(smallWidth).toBeLessThan(55);}
    else expect(geometry.width).toBeGreaterThan(smallWidth);
    await expect(page.getByRole("button",{name:"Ajustar ancho de #",exact:true})).toHaveCount(0);
  }
});
