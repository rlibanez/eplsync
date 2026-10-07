import { test, expect } from "./fixtures";

test("update preferences persist and covers move into catalog settings", async ({page}) => {
  let saved = {states: ["DOWNLOADED"]};
  let writes = 0;
  await page.route("**/api/settings/downloads/updates", route => {
    if(route.request().method() === "PUT") {saved = route.request().postDataJSON(); writes++;}
    return route.fulfill({json:saved});
  });
  await page.route("**/api/catalog/covers/config", route => route.fulfill({json:{connectTimeoutMs:1000,requestTimeoutMs:2000,batchTimeoutMs:3000,concurrency:2}}));
  await page.route("**/api/settings/covers", route => route.fulfill({json:{section:"covers", fields:[]}}));
  await page.goto("/settings/downloads");
  await expect(page.getByRole("heading", {name:"Configuración de actualizaciones de revisión"})).toBeVisible();
  const boxes=page.locator(".settings-section input[type=checkbox]");
  await expect(boxes).toHaveCount(10);
  await boxes.last().check();
  await page.getByRole("button", {name:"Guardar",exact:true}).click();
  await expect.poll(() => saved.states).toContain("NOT_FOUND");
  await expect(page.getByRole("status").filter({hasText:"Configuración guardada."})).toHaveClass("muted");
  await page.reload();
  await expect(boxes.last()).toBeChecked();
  await page.getByRole("button", {name:"Restablecer valores predeterminados"}).click();
  for (let i=0; i<10; i++) await expect(boxes.nth(i)).toBeChecked();
  expect(writes).toBe(1);
  await page.goto("/settings/covers");
  await expect(page).toHaveURL(/\/settings\/catalog$/);
  await expect(page.getByRole("heading", {name:"Comprobar portadas",exact:true})).toBeVisible();
  await expect(page.locator(".settings-tabs").getByRole("link",{name:"Portadas",exact:true})).toHaveCount(0);
});
