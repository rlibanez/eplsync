import { test, expect } from "./fixtures";
test.use({ timezoneId: "Europe/Madrid" });
test("event deletion supports inclusive dates, open ranges and explicit all", async ({ page }) => {
  await page.clock.setFixedTime(new Date("2026-10-04T12:00:00Z"));
  await page.route("**/api/settings/events", r => r.fulfill({json: {section: "events", fields: []}}));
  const requests: unknown[] = [];
  await page.route("**/api/events/delete", r => {
    requests.push(r.request().postDataJSON());
    return r.fulfill({json: {deleted: 1}});
  });
  await page.goto("/settings/events");
  await expect(page.getByRole("heading", {name: "Configuración de eventos"})).toBeVisible();
  const section = page.locator("section#events");
  const remove = section.getByRole("button", {name: "Eliminar eventos", exact: true});
  await expect(remove).toBeDisabled();
  async function confirm() {
    await remove.click();
    await page.getByRole("dialog").getByRole("button", {name: "Eliminar eventos", exact: true}).click();
    await expect(page.getByRole("dialog")).toHaveCount(0);
  }
  await section.getByLabel("Desde", {exact: true}).fill("2026-10-01");
  await confirm();
  expect(requests[0]).toEqual({confirm: true, from: "2026-09-30T22:00:00.000Z", before: "2026-10-04T22:00:00.000Z"});
  await section.getByLabel("Desde", {exact: true}).fill("");
  await section.getByLabel("Hasta", {exact: true}).fill("2026-03-29");
  await confirm();
  expect(requests[1]).toEqual({confirm: true, before: "2026-03-29T22:00:00.000Z"});
  await section.getByLabel("Desde", {exact: true}).fill("2026-03-29");
  await confirm();
  expect(requests[2]).toEqual({confirm: true, from: "2026-03-28T23:00:00.000Z", before: "2026-03-29T22:00:00.000Z"});
  await section.getByLabel("Borrar", {exact: true}).click();
  await expect(page.getByRole("listbox").getByRole("option")).toHaveCount(2);
  await page.getByRole("option", {name: "Todos los eventos", exact: true}).click();
  await confirm();
  expect(requests[3]).toEqual({confirm: true});
});
