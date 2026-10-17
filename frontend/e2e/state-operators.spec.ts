import { test, expect } from "./fixtures";
import { fulfillSavedSearch } from "./saved-search-response";

const empty = {items:[],meta:{page:0,size:20,totalItems:0,totalPages:0,hasNext:false,hasPrevious:false}};
for (const [path, endpoint, key, option] of [
  ["/downloads/jobs", "/torrent/jobs", "status", "Terminado"],
  ["/downloads/jobs/job", "/torrent/jobs/job/items", "status", "Aceptado"],
  ["/calibre/jobs", "/calibre/transfers", "state", "Terminado"],
  ["/calibre/jobs/job", "/calibre/transfers/job/items", "state", "Enviado"],
] as const) {
  test(`${path}: state exclusion is sent on every page and clearing resets it`, async ({page}) => {
    await page.route("**/api/torrent/jobs/job", r=>r.fulfill({json:{jobId:"job",status:"COMPLETED",type:"DOWNLOAD",client:"qbittorrent",selectedItems:0,processedItems:0,selectedBooks:0,accepted:0,failed:0,alreadyExists:0,skipped:0,pending:0,inFlight:0,cancelled:0,batchSize:1,concurrency:1,interval:"1s",createdAt:"2026-10-17T00:00:00Z"}}));
    await page.route("**/api/calibre/transfers/job", r=>r.fulfill({json:{id:"job",state:"COMPLETED",library:"main",total:0,pending:0,accepted:0,skipped:0,failed:0,uncertain:0}}));
    const requests: URLSearchParams[]=[];
    await page.route(`**/api${endpoint}?**`, r=> {
      const p=new URL(r.request().url()).searchParams; requests.push(p);
      return r.fulfill({json:{...empty,meta:{...empty.meta,page:Number(p.get("page")),totalItems:40,totalPages:2,hasNext:p.get("page")==="0",hasPrevious:p.get("page")==="1"}}});
    });
    await page.goto(path);
    const field=page.getByRole("textbox",{name:"Estado",exact:true});
    const stateControl=page.locator(".jobs-filters > div").filter({has:field});
    await field.click();
    await page.getByRole("option",{name:option,exact:true}).click();
    await page.keyboard.press("Escape");
    await stateControl.getByRole("button",{name:"Incluir valores seleccionados",exact:true}).click();
    await expect.poll(()=>requests.at(-1)?.get("statusOperator")).toBe("ne");
    await page.getByRole("button",{name:"Siguiente",exact:true}).click();
    await expect.poll(()=>requests.at(-1)?.get("page")).toBe("1");
    expect(requests.at(-1)?.get("statusOperator")).toBe("ne");
    expect(requests.at(-1)?.get(key)).toBeTruthy();
    await field.click();
    await page.getByRole("option",{name:option,exact:true}).click();
    await page.keyboard.press("Escape");
    await expect(stateControl.getByRole("button",{name:"Incluir valores seleccionados",exact:true})).toHaveText("=");
  });
}

test("catalog keeps all three state operators in the URL and resets them on clear", async ({page}) => {
  const requests:URLSearchParams[]=[];
  await page.route("**/api/catalog/books?**", r=>{requests.push(new URL(r.request().url()).searchParams);return r.fulfill({json:empty});});
  await page.goto("/catalog?status=VERIFICADO&statusOperator=ne&publicationStatus=UPDATED&publicationStatusOperator=ne&torrentStatus=DOWNLOADED&torrentStatusOperator=ne");
  await page.locator(".catalog-search summary").click();
  await expect(page.getByRole("button",{name:"Excluir valores seleccionados",exact:true})).toHaveCount(3);
  await page.getByRole("button",{name:"Buscar",exact:true}).click();
  await expect.poll(()=>requests.at(-1)?.get("torrentStatusOperator")).toBe("ne");
  expect(requests.at(-1)?.get("statusOperator")).toBe("ne");
  expect(requests.at(-1)?.get("publicationStatusOperator")).toBe("ne");
  await page.reload();
  await page.locator(".catalog-search summary").click();
  await expect(page.getByRole("button",{name:"Excluir valores seleccionados",exact:true})).toHaveCount(3);
  await page.getByRole("button",{name:"Limpiar",exact:true}).click();
  await expect(page.getByRole("button",{name:"Incluir valores seleccionados",exact:true})).toHaveCount(4);
});

test("saved download updates exclude states also when selecting all results", async ({page}) => {
  await page.route("**/api/settings/downloads/updates", r=>r.fulfill({json:{states:["DOWNLOADED","ERROR"]}}));
  const requests:URLSearchParams[]=[];
  const search={searchedAt:123,states:["DOWNLOADED","ERROR"],rows:Array.from({length:45},(_,i)=>({eplId:i+1,title:`Book ${i}`,registeredRevision:1,availableRevision:2,status:i<5?"DOWNLOADED":"ERROR"}))};
  await page.route("**/api/settings/tasks/searches/downloads**", r=> {
    if(new URL(r.request().url()).pathname.endsWith("/rows")) requests.push(new URL(r.request().url()).searchParams);
    return fulfillSavedSearch(r,{json:{search}});
  });
  await page.goto("/downloads/updates");
  const field=page.getByRole("textbox",{name:"Estado registrado",exact:true});
  await field.click();await page.getByRole("option",{name:"Descargado",exact:true}).click();await page.keyboard.press("Escape");
  await page.getByRole("button",{name:"Incluir valores seleccionados",exact:true}).click();
  await expect(page.getByRole("button",{name:"Seleccionar todo (40)",exact:true})).toBeVisible();
  await page.getByRole("button",{name:"Siguiente",exact:true}).click();
  await expect.poll(()=>requests.at(-1)?.get("page")).toBe("1");
  expect(requests.at(-1)?.get("statusOperator")).toBe("ne");
  await page.getByRole("button",{name:"Seleccionar todo (40)",exact:true}).click();
  await expect(page.getByRole("button",{name:"Quitar selección (40)",exact:true})).toBeVisible();
  expect(requests.filter(p=>p.get("size")==="1000").every(p=>p.get("statusOperator")==="ne")).toBe(true);
});
