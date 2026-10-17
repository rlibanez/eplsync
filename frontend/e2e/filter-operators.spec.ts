import { test, expect, type Page } from "./fixtures";
import { fulfillSavedSearch } from "./saved-search-response";
const empty={items:[],meta:{page:0,size:20,totalItems:40,totalPages:2,hasNext:true,hasPrevious:false}};
const control=(page:Page,label:string)=>page.locator(".mantine-InputWrapper-root").filter({has:page.getByRole("textbox",{name:label,exact:true})});

for(const [path,endpoint,label,option,key] of [
  ["/downloads","/torrent/downloads","Origen","EPL Sync","origin"],
  ["/downloads/jobs","/torrent/jobs","Tipo","Limpieza","type"],
  ["/calibre/jobs","/calibre/transfers","Tipo","Limpieza","type"],
] as const) test(`${path}: excluding ${key} survives paging and clears`,async({page})=>{
  const requests:URLSearchParams[]=[];
  await page.route(`**/api${endpoint}?**`,r=>{const p=new URL(r.request().url()).searchParams;requests.push(p);return r.fulfill({json:{...empty,meta:{...empty.meta,page:Number(p.get("page")),hasPrevious:p.get("page")==="1",hasNext:p.get("page")==="0"}}});});
  await page.goto(path);
  await page.getByRole("textbox",{name:label,exact:true}).click();await page.getByRole("option",{name:option,exact:true}).click();
  await control(page,label).getByRole("button",{name:"Incluir valores seleccionados",exact:true}).click();
  await expect.poll(()=>requests.at(-1)?.get(`${key}Operator`)).toBe("ne");
  await page.getByRole("button",{name:"Siguiente",exact:true}).click();
  await expect.poll(()=>requests.at(-1)?.get("page")).toBe("1");expect(requests.at(-1)?.get(`${key}Operator`)).toBe("ne");
  if(key === "origin") {
    const all=page.waitForRequest(r=>new URL(r.url()).searchParams.get("size")==="1000" && new URL(r.url()).searchParams.get("originOperator")==="ne");
    await page.getByRole("button",{name:"Seleccionar todo (40)",exact:true}).click();await all;
  }
  await page.getByRole("button",{name:"Limpiar",exact:true}).click();
  await expect(control(page,label).getByRole("button",{name:"Incluir valores seleccionados",exact:true})).toHaveText("=");
});

test("catalog negative title, author and language persist and clear",async({page})=>{
  const requests:URLSearchParams[]=[];
  await page.route("**/api/catalog/suggestions/**",r=>r.fulfill({json:{items:[],total:0,nextOffset:null}}));
  await page.route("**/api/catalog/books?**",r=>{requests.push(new URL(r.request().url()).searchParams);return r.fulfill({json:{...empty,meta:{...empty.meta,totalItems:0,totalPages:0,hasNext:false}}});});
  await page.goto("/catalog");await page.locator(".catalog-search summary").click();
  for(const [label,value] of [["Título","Dune"],["Autor","Herbert"]]) {
    await control(page,label).getByRole("button",{name:"Contiene alguno de los textos indicados",exact:true}).click();
    await page.getByRole("textbox",{name:label,exact:true}).fill(value);await page.getByRole("textbox",{name:label,exact:true}).press("Enter");
  }
  await page.getByRole("textbox",{name:"Idioma",exact:true}).click();await page.getByRole("option",{name:"Español",exact:true}).click();await page.keyboard.press("Escape");
  await control(page,"Idioma").getByRole("button",{name:"Incluir valores seleccionados",exact:true}).click();
  await page.getByRole("button",{name:"Buscar",exact:true}).click();
  await expect.poll(()=>requests.at(-1)?.get("titleOperator")).toBe("ne");expect(requests.at(-1)?.get("authorOperator")).toBe("ne");expect(requests.at(-1)?.get("languageOperator")).toBe("ne");
  await page.reload();await page.locator(".catalog-search summary").click();
  await expect(page.getByRole("button",{name:"No contiene ninguno de los textos indicados",exact:true})).toHaveCount(2);
  await page.getByRole("button",{name:"Limpiar",exact:true}).click();
  await expect(page.getByRole("button",{name:"Contiene alguno de los textos indicados",exact:true})).toHaveCount(2);
});

for(const updates of [false,true]) test(`Calibre ${updates?"updates":"library"}: text exclusion reaches every selected page`,async({page})=>{
  const requests:URLSearchParams[]=[];
  await page.route("**/api/settings/calibre/snapshot",r=>r.fulfill({json:{snapshot:{library:"main",total:40,identified:40,syncedAt:"2026-10-17T00:00:00Z"}}}));
  await page.route("**/api/calibre/books?**",r=>{const p=new URL(r.request().url()).searchParams;requests.push(p);return r.fulfill({json:{...empty,meta:{...empty.meta,page:Number(p.get("page")),hasNext:p.get("page")==="0",hasPrevious:p.get("page")==="1"}}});});
  const search={searchedAt:123,rows:Array.from({length:45},(_,i)=>({calibreId:i+1,eplId:i+1,title:i<5?"Dune":"Foundation",authors:i<5?"Herbert":"Asimov",revision:1,catalogRevision:2,comparison:"OUTDATED",presence:"PRESENT"}))};
  await page.route("**/api/settings/tasks/searches/calibre**",r=>{if(new URL(r.request().url()).pathname.endsWith("/rows"))requests.push(new URL(r.request().url()).searchParams);return fulfillSavedSearch(r,{json:{search}});});
  await page.goto(updates?"/calibre/updates":"/calibre");
  for(const [label,value] of [["Libro","Dune"],["Autor","Herbert"]]) {
    await control(page,label).getByRole("button",{name:"Contiene alguno de los textos indicados",exact:true}).click();
    await page.getByRole("textbox",{name:label,exact:true}).fill(value);
  }
  await expect.poll(()=>requests.at(-1)?.get("authorOperator")).toBe("ne");
  await page.getByRole("button",{name:"Siguiente",exact:true}).click();
  await expect.poll(()=>requests.at(-1)?.get("page")).toBe("1");expect(requests.at(-1)?.get("titleOperator")).toBe("ne");
  const all=page.waitForRequest(r=>new URL(r.url()).searchParams.get("size")==="1000" && new URL(r.url()).searchParams.get("authorOperator")==="ne");
  await page.getByRole("button",{name:"Seleccionar todo (40)",exact:true}).click();await all;
  await page.getByRole("button",{name:"Limpiar",exact:true}).click();
  await expect(page.getByRole("button",{name:"Contiene alguno de los textos indicados",exact:true})).toHaveCount(2);
});

test("all event filter operators combine, paginate and persist",async({page})=>{
  const requests:URLSearchParams[]=[];
  await page.route("**/api/events/operations?**",r=>{const p=new URL(r.request().url()).searchParams;requests.push(p);return r.fulfill({json:{items:[],total:40,page:Number(p.get("page")),size:20,cursor:0}});});
  await page.goto("/events");
  for(const [label,option] of [["Categoría","Catálogo"],["Evento","Actualización del catálogo"],["Resultado","Completado"],["Origen","Programado"]]) {
    await page.getByRole("textbox",{name:label,exact:true}).click();await page.getByRole("option",{name:option,exact:true}).click();
    await control(page,label).getByRole("button",{name:"Incluir valores seleccionados",exact:true}).click();
  }
  await expect.poll(()=>requests.at(-1)?.get("originOperator")).toBe("ne");
  for(const key of ["category","action","outcome","origin"])expect(requests.at(-1)?.get(`${key}Operator`)).toBe("ne");
  await page.getByRole("button",{name:"Siguiente",exact:true}).click();await expect.poll(()=>requests.at(-1)?.get("page")).toBe("1");
  await page.reload();await expect(page.getByRole("button",{name:"Excluir valores seleccionados",exact:true})).toHaveCount(4);
  await page.getByRole("button",{name:"Limpiar",exact:true}).click();await expect(page.getByRole("button",{name:"Incluir valores seleccionados",exact:true})).toHaveCount(4);
});
