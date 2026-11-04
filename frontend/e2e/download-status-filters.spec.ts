import { test, expect } from "./fixtures";

test("download status accepts multiple alternatives and preserves other filters", async ({
  page,
}) => {
  const queries: URLSearchParams[] = [];
  await page.route("**/api/torrent/downloads?**", (r) => {
    const params = new URL(r.request().url()).searchParams;
    queries.push(params);
    return r.fulfill({
      json: {
        items: [],
        meta: {
          page: Number(params.get("page")),
          size: Number(params.get("size")),
          totalItems: 0,
          totalPages: 0,
          hasNext: false,
          hasPrevious: false,
        },
      },
    });
  });
  await page.goto("/downloads");
  await page.getByRole("textbox", { name: "Origen", exact: true }).click();
  await page.getByRole("option", { name: "EPL Sync", exact: true }).click();
  const status = page.getByRole("textbox", { name: "Estado", exact: true });
  await status.click();
  await page.getByRole("option", { name: "Descargado", exact: true }).click();
  const pill = page
    .locator(".jobs-filters .mantine-Pill-root")
    .filter({ hasText: "Descargado" });
  const pillBox = await pill.boundingBox();
  const searchBox = await status.boundingBox();
  expect(Math.abs(pillBox!.y - searchBox!.y)).toBeLessThan(5);
  await status.click();
  await page.getByRole("option", { name: "Descargando", exact: true }).click();
  await expect
    .poll(() => queries.at(-1)?.get("status"))
    .toBe("DOWNLOADED,DOWNLOADING");
  expect(queries.at(-1)?.get("origin")).toBe("EPLSYNC");
  expect(queries.at(-1)?.get("page")).toBe("0");
  await status.press("Escape");
  const pills = page.locator(".jobs-filters .mantine-Pill-root");
  await pills
    .filter({ hasText: "Descargado" })
    .getByRole("button", { includeHidden: true })
    .click();
  await expect.poll(() => queries.at(-1)?.get("status")).toBe("DOWNLOADING");
  await page.getByRole("button", { name: "Limpiar", exact: true }).click();
  await expect(pills).toHaveCount(0);
  await expect(
    page.getByRole("textbox", { name: "Origen", exact: true }),
  ).toHaveValue("");
  // Clearing can reuse the initial cached result; make a new query to verify its filters.
  await page.getByRole("textbox", { name: "EPL ID", exact: true }).fill("777");
  await page
    .getByRole("textbox", { name: "EPL ID", exact: true })
    .press("Enter");
  await expect.poll(() => queries.at(-1)?.get("eplId")).toBe("777");
  expect(queries.at(-1)?.has("status")).toBe(false);
  expect(queries.at(-1)?.has("origin")).toBe(false);
  await expect(pills).toHaveCount(1);
  await page
    .getByRole("button", { name: "Limpiar: EPL ID", exact: true })
    .click();
  await expect(pills).toHaveCount(0);
});


test("download extensions use server filters and expose an optional local inventory column",async({page})=>{
  await page.route("**/api/torrent/downloads/extensions",r=>r.fulfill({json:["epub","pdf","rar"]}));
  await page.route("**/api/torrent/downloads?**",r=>r.fulfill({json:{items:[{id:"inventory",eplId:1,title:"Inventory book",revision:1,hash:"A".repeat(40),status:"DOWNLOADED",client:"qbittorrent",origin:"EPLSYNC",inventoryState:"CHECKED",inventoryCheckedAt:"2026-11-04T10:00:00Z",fileExtensions:["epub","pdf"]}],meta:{page:0,size:20,totalItems:1,totalPages:1,hasNext:false,hasPrevious:false}}}));
  await page.goto("/downloads");await page.getByRole("textbox",{name:"Extensiones",exact:true}).fill("PDF");
  const filtered=page.waitForRequest(r=>new URL(r.url()).pathname==="/api/torrent/downloads" && new URL(r.url()).searchParams.get("extensions")==="pdf");
  await page.getByRole("option",{name:"PDF",exact:true}).click();await filtered;
  const negated=page.waitForRequest(r=>new URL(r.url()).pathname==="/api/torrent/downloads" && new URL(r.url()).searchParams.get("extensionsOperator")==="ne");
  const field=page.getByRole("textbox",{name:"Extensiones",exact:true}).locator("xpath=ancestor::*[contains(@class,'mantine-MultiSelect-root')]");
  await field.getByRole("button",{name:"Incluir valores seleccionados",exact:true}).click();await negated;
});

test("extensions are visible by default, sortable and include unknown inventory",async({page})=>{
  await page.route("**/api/torrent/downloads?**",r=>r.fulfill({json:{items:[{id:"unverified",eplId:1,title:"Unverified",revision:1,hash:"A".repeat(40),status:"DOWNLOADED",client:"qbittorrent",origin:"EPLSYNC",inventoryState:"PENDING",inventoryCheckedAt:null,fileExtensions:[]}],meta:{page:0,size:20,totalItems:1,totalPages:1,hasNext:false,hasPrevious:false}}}));
  await page.addInitScript(()=>localStorage.setItem("eplsync.downloads.columnWidths.settings.v1",JSON.stringify({order:["eplId","title","status","fileExtensions"],hidden:["fileExtensions"],widths:{}})));
  await page.goto("/downloads");
  const column=page.locator('th[data-table-column="fileExtensions"]');await expect(column).toBeVisible();
  const sorted=page.waitForRequest(r=>r.url().includes("/api/torrent/downloads?") && new URL(r.url()).searchParams.getAll("sort").includes("fileExtensions,asc"));
  await column.click();await sorted;
  const filtered=page.waitForRequest(r=>r.url().includes("/api/torrent/downloads?") && new URL(r.url()).searchParams.get("extensions")==="UNKNOWN");
  await page.locator("tbody").getByRole("button",{name:"Sin comprobar",exact:true}).click();await filtered;
});

test("one extension filter includes other formats and failed checks", async ({page}) => {
  await page.route("**/api/torrent/downloads/extensions", r=>r.fulfill({json:["cbz","pdf","txt"]}));
  await page.route("**/api/torrent/downloads?**", r=>r.fulfill({json:{items:[{id:"failed",eplId:1,title:"Failed download",revision:1,hash:"A".repeat(40),status:"DOWNLOADED",client:"qbittorrent",origin:"EPLSYNC",inventoryState:"ERROR",inventoryCheckedAt:null,inventoryError:"Check failed",fileExtensions:[]}],meta:{page:0,size:20,totalItems:1,totalPages:1,hasNext:false,hasPrevious:false}}}));
  await page.goto("/downloads");
  await expect(page.getByRole("textbox",{name:"Inventario",exact:true})).toHaveCount(0);
  const field=page.getByRole("textbox",{name:"Extensiones",exact:true});
  await field.click();
  for(const name of ["Otro","Sin comprobar","Error de comprobación","CBR","CBZ","TXT"]) await expect(page.getByRole("option",{name,exact:true})).toBeVisible();
  const other=page.waitForRequest(r=>r.url().includes("/api/torrent/downloads?") && new URL(r.url()).searchParams.get("extensions")==="OTHER");
  await page.getByRole("option",{name:"Otro",exact:true}).click();await other;
  const combined=page.waitForRequest(r=>r.url().includes("/api/torrent/downloads?") && new URL(r.url()).searchParams.get("extensions")==="OTHER,ERROR");
  await page.getByRole("option",{name:"Error de comprobación",exact:true}).click();await combined;
  await field.press("Escape");
  await expect(page.locator("tbody").getByRole("button",{name:"Error de comprobación",exact:true})).toBeVisible();
  const excluded=page.waitForRequest(r=>r.url().includes("/api/torrent/downloads?") && new URL(r.url()).searchParams.get("extensionsOperator")==="ne");
  await field.locator("xpath=ancestor::*[contains(@class,'mantine-MultiSelect-root')]").getByRole("button",{name:"Incluir valores seleccionados",exact:true}).click();await excluded;
  await page.getByRole("button",{name:"Limpiar: Extensiones",exact:true}).click();
  await expect(field.locator("xpath=ancestor::*[contains(@class,'mantine-MultiSelect-root')]").locator(".mantine-Pill-root")).toHaveCount(0);
});
