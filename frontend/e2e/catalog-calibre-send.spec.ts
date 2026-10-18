import { test, expect } from "./fixtures";
const book = {eplId: 2, revision: 1.1, title: "Book 2"};
const file = {recordId: "download-2", index: 0, name: "Book.epub", size: 200, expectedSize: 200, eplId: 2, revision: 1.1};
test.beforeEach(async ({page}) => {
  await page.route("**/api/catalog/books?**", route => {
    const n=Number(new URL(route.request().url()).searchParams.get("page") ?? 0);
    return route.fulfill({json:{items:[1,2].map(id=>({eplId:id+n*2,title:`Book ${id+n*2}`,author:"Author",revision:1.1,language:"es",download:{items:[]}})),meta:{page:n,size:20,totalItems:40,totalPages:2,hasNext:n===0,hasPrevious:n>0}}});
  });
  await page.route("**/api/calibre/transfers/catalog-job",route=>route.fulfill({json:{id:"catalog-job",state:"QUEUED",total:1,pending:1,accepted:0,skipped:0,failed:0,uncertain:0,library:"main"}}));
  await page.route("**/api/calibre/transfers/catalog-job/items?**",route=>route.fulfill({json:{items:[],meta:{page:0,size:20,totalItems:0,totalPages:0}}}));
});
test("explicit catalog selection uses the shared EPUB assistant and creates a Calibre job", async ({page}) => {
  await page.route("**/api/calibre/transfers/catalog/prepare", route=>{
    expect(route.request().postDataJSON()).toEqual({filters:{selectedIds:[2]}});
    return route.fulfill({json:{library:"main",libraries:[{id:"main",name:"Main"}],files:[file],books:[book],missing:[]}});
  });
  await page.route("**/api/calibre/transfers/catalog", route=>{
    expect(route.request().postDataJSON()).toMatchObject({library:"main",books:[book],files:[{recordId:"download-2",index:0}],download:false,pending:[]});
    expect(route.request().postDataJSON().id).toBeTruthy();
    return route.fulfill({json:{id:"catalog-job"}});
  });
  await page.goto("/catalog");
  await expect(page.getByRole("button",{name:"Enviar a Calibre",exact:true})).toHaveCount(0);
  await page.getByRole("checkbox",{name:"Seleccionar Book 2",exact:true}).check();
  await expect(page.locator(".selection-toolbar-actions button")).toHaveText(["Exportar magnet links", "Enviar a descargar", "Enviar a Calibre"]);
  await page.getByRole("button",{name:"Enviar a Calibre",exact:true}).click();
  const dialog=page.getByRole("dialog");
  await expect(dialog.getByRole("checkbox",{name:/Book.epub/})).toBeChecked();
  await dialog.getByRole("button",{name:"Enviar a Calibre",exact:true}).click();
  await expect(page).toHaveURL(/\/calibre\/jobs\/catalog-job$/);
});
test("select all retains library filters and exclusions and requires opt-in for missing EPUBs", async ({page}) => {
  await page.route("**/api/calibre/transfers/catalog/prepare", route=>{
    expect(route.request().postDataJSON()).toEqual({filters:{torrentPresence:"PRESENT",torrentRevision:"UPDATED",torrentStatus:["DOWNLOADED","QUEUED"],calibrePresence:"ABSENT",excludedIds:[1]}});
    return route.fulfill({json:{library:"main",libraries:[{id:"main",name:"Main"}],files:[],books:[book],missing:[book]}});
  });
  await page.route("**/api/calibre/transfers/catalog", route=>{
    expect(route.request().postDataJSON()).toMatchObject({library:"main",books:[book],files:[],download:true,pending:[2]});
    return route.fulfill({json:{id:"catalog-job"}});
  });
  await page.goto("/catalog?torrentPresence=PRESENT&torrentRevision=UPDATED&torrentStatus=DOWNLOADED&torrentStatus=QUEUED&calibrePresence=ABSENT");
  await page.getByRole("button",{name:"Seleccionar todo (40)",exact:true}).click();
  await page.getByRole("checkbox",{name:"Seleccionar Book 1",exact:true}).uncheck();
  await page.getByRole("button",{name:"Siguiente",exact:true}).click();
  await page.getByRole("button",{name:"Enviar a Calibre",exact:true}).click();
  const dialog=page.getByRole("dialog");
  await expect(dialog.locator("fieldset").filter({has: dialog.locator("legend", {hasText:"Archivos EPUB"})})).toHaveCount(0);
  await expect(dialog.getByRole("button",{name:"Enviar a Calibre",exact:true})).toBeDisabled();
  await dialog.getByRole("checkbox",{name:"Descargar los EPUB no disponibles y enviarlos a Calibre cuando terminen",exact:true}).check();
  await dialog.getByRole("button",{name:"Enviar a Calibre",exact:true}).click();
  await expect(page).toHaveURL(/\/calibre\/jobs\/catalog-job$/);
});
test("catalog readers without file access cannot send to Calibre", async ({page}) => {
  await page.route("**/api/auth/me",route=>route.fulfill({json:{id:"reader",username:"reader",role:"USER",permissions:["CATALOG_READ","CALIBRE_SEND"],mustChangePassword:false}}));
  await page.goto("/catalog");
  await page.getByRole("checkbox",{name:"Seleccionar Book 2",exact:true}).check();
  await expect(page.getByRole("button",{name:"Enviar a Calibre",exact:true})).toHaveCount(0);
});
test("whole catalog can be sent to Calibre as a single job", async ({page}) => {
  const books=Array.from({length:73000},(_,n)=>({eplId:n+1,revision:1,title:`Book ${n+1}`}));
  await page.route("**/api/catalog/books?**",route=>route.fulfill({json:{items:[{eplId:1,title:"Book 1",author:"Author",revision:1,download:{items:[]}}],meta:{page:0,size:20,totalItems:73000,totalPages:3650,hasNext:true,hasPrevious:false}}}));
  await page.route("**/api/calibre/transfers/catalog/prepare",route=>route.fulfill({json:{library:"main",libraries:[{id:"main",name:"Main"}],files:[],books,missing:books,deferred:[]}}));
  await page.route("**/api/calibre/transfers/catalog",route=>{
    const request=route.request().postDataJSON();
    expect(request.books).toHaveLength(73000);expect(request.pending).toHaveLength(73000);expect(request.download).toBe(true);
    return route.fulfill({json:{id:"catalog-job"}});
  });
  await page.goto("/catalog");
  await page.getByRole("button",{name:/Seleccionar todo/}).click();
  await page.getByRole("button",{name:"Enviar a Calibre",exact:true}).click();
  const dialog=page.getByRole("dialog");
  await dialog.getByRole("checkbox",{name:/Descargar los EPUB no disponibles/}).check();
  await dialog.getByRole("button",{name:"Enviar a Calibre",exact:true}).click();
  await expect(page).toHaveURL(/\/calibre\/jobs\/catalog-job$/);
});
test("large downloaded selections defer file checks without requesting downloads",async({page})=>{
  const books=Array.from({length:1001},(_,n)=>({eplId:n+1,revision:1,title:`Book ${n+1}`}));
  await page.route("**/api/calibre/transfers/catalog/prepare",route=>route.fulfill({json:{library:"main",libraries:[{id:"main",name:"Main"}],files:[],books,missing:[],deferred:books}}));
  await page.route("**/api/calibre/transfers/catalog",route=>{
    const request=route.request().postDataJSON();expect(request.pending).toHaveLength(1001);expect(request.download).toBe(false);
    return route.fulfill({json:{id:"catalog-job"}});
  });
  await page.goto("/catalog");await page.getByRole("button",{name:/Seleccionar todo/}).click();
  await page.getByRole("button",{name:"Enviar a Calibre",exact:true}).click();
  const dialog=page.getByRole("dialog");
  await expect(dialog.getByText(/Se comprobarán los EPUB de 1001 libros/)).toBeVisible();
  await expect(dialog.getByRole("checkbox",{name:/Descargar los EPUB/})).toHaveCount(0);
  await dialog.getByRole("button",{name:"Enviar a Calibre",exact:true}).click();
  await expect(page).toHaveURL(/\/calibre\/jobs\/catalog-job$/);
});
test("large file previews are paged without losing selected or excluded EPUBs",async({page})=>{
  const files=Array.from({length:1001},(_,n)=>({...file,recordId:`record-${n}`,name:`File ${n}.epub`}));
  await page.route("**/api/calibre/transfers/catalog/prepare",route=>route.fulfill({json:{library:"main",libraries:[{id:"main",name:"Main"}],files,books:[book],missing:[]}}));
  await page.route("**/api/calibre/transfers/catalog",route=>{
    const request=route.request().postDataJSON();expect(request.files).toHaveLength(1000);expect(request.files.some((entry:{recordId:string})=>entry.recordId==="record-1000")).toBe(false);
    return route.fulfill({json:{id:"catalog-job"}});
  });
  await page.goto("/catalog");await page.getByRole("checkbox",{name:"Seleccionar Book 2",exact:true}).check();
  await page.getByRole("button",{name:"Enviar a Calibre",exact:true}).click();const dialog=page.getByRole("dialog");
  await expect(dialog.getByRole("checkbox")).toHaveCount(100);
  for(let n=0;n<10;n++) await dialog.getByRole("button",{name:"Siguiente",exact:true}).click();
  await dialog.getByRole("checkbox",{name:/File 1000.epub/}).uncheck();
  await dialog.getByRole("button",{name:"Enviar a Calibre",exact:true}).click();
  await expect(page).toHaveURL(/\/calibre\/jobs\/catalog-job$/);
});

test("available EPUBs appear before the missing download option", async ({page}) => {
  await page.route("**/api/calibre/transfers/catalog/prepare", route=>route.fulfill({json:{library:"main",libraries:[{id:"main",name:"Main"}],files:[file],books:[book],missing:[{eplId:1,revision:1,title:"Pending book"}]}}));
  await page.goto("/catalog");
  await page.getByRole("button",{name:"Seleccionar todo (40)",exact:true}).click();
  await page.getByRole("button",{name:"Enviar a Calibre",exact:true}).click();
  const dialog=page.getByRole("dialog");
  const files=dialog.locator("fieldset").filter({hasText:"Archivos EPUB"});
  const pending=dialog.getByRole("checkbox",{name:/Descargar los EPUB no disponibles/});
  await expect(files).toBeVisible();
  await expect(pending).toBeVisible();
  const fileBounds=await files.boundingBox();
  const pendingBounds=await pending.boundingBox();
  expect(pendingBounds!.y).toBeGreaterThan(fileBounds!.y+fileBounds!.height);
});
