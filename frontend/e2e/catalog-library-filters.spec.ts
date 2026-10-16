import { test, expect } from "./fixtures";
const empty = {
  items: [],
  meta: {
    page: 0,
    size: 20,
    totalItems: 0,
    totalPages: 0,
    hasNext: false,
    hasPrevious: false,
  },
};
test.beforeEach(async ({ page }) => {
  await page.route("**/api/catalog/books?**", (route) =>
    route.fulfill({ json: empty }),
  );
});
for (const library of ["Torrent", "Calibre"]) {
  test(`${library}: blank defaults, revision implies presence, absence clears revision and URL survives reload`, async ({
    page,
  }) => {
    await page.goto("/catalog");
    await page.locator(".catalog-search summary").click();
    const group = page.getByRole("group", { name: library, exact: true });
    const presence = group.getByRole("textbox", {
      name: "Presencia",
      exact: true,
    });
    const revision = group.getByRole("textbox", {
      name: "Revisión",
      exact: true,
    });
    await expect(presence).toHaveValue("");
    await expect(revision).toHaveValue("");
    await expect(revision).toBeEnabled();
    await revision.click();
    await page
      .getByRole("option", { name: "Actualizada", exact: true })
      .click();
    await expect(presence).toHaveValue("Presentes");
    await presence.click();
    await page.getByRole("option", { name: "Ausentes", exact: true }).click();
    await expect(revision).toHaveValue("");
    await expect(revision).toBeDisabled();
    await group
      .getByRole("button", {
        name: `Quitar filtro de presencia de ${library}`,
        exact: true,
      })
      .click();
    await expect(presence).toHaveValue("");
    await expect(revision).toBeEnabled();
    await revision.click();
    await page.getByRole("option", { name: "Anterior", exact: true }).click();
    const request = page.waitForRequest(
      (r) =>
        r.url().includes("/api/catalog/books?") &&
        new URL(r.url()).searchParams.get(
          `${library.toLowerCase()}Revision`,
        ) === "OUTDATED",
    );
    await page.getByRole("button", { name: "Buscar", exact: true }).click();
    const params = new URL((await request).url()).searchParams;
    expect(params.get(`${library.toLowerCase()}Presence`)).toBe("PRESENT");
    await expect(page).toHaveURL(
      new RegExp(`${library.toLowerCase()}Revision=OUTDATED`),
    );
    await page.reload();
    await page.locator(".catalog-search summary").click();
    await expect(presence).toHaveValue("Presentes");
    await expect(revision).toHaveValue("Anterior");
    await page
      .getByRole("button", { name: "Limpiar filtros", exact: true })
      .click();
    await expect(page).not.toHaveURL(
      new RegExp(`${library.toLowerCase()}Revision`),
    );
  });
}
test("combines both libraries without widening either filter and fits mobile", async ({
  page,
}) => {
  await page.goto("/catalog?torrentPresence=PRESENT&calibrePresence=ABSENT");
  await page.locator(".catalog-search summary").click();
  await expect(
    page
      .getByRole("group", { name: "Torrent", exact: true })
      .getByRole("textbox", { name: "Presencia", exact: true }),
  ).toHaveValue("Presentes");
  await expect(
    page
      .getByRole("group", { name: "Calibre", exact: true })
      .getByRole("textbox", { name: "Revisión", exact: true }),
  ).toBeDisabled();
  await page.screenshot({
    path: "/tmp/eplsync-catalog-library-filters.png",
    fullPage: true,
  });
  await page.setViewportSize({ width: 390, height: 844 });
  expect(await page.evaluate(() => document.documentElement.scrollWidth)).toBe(
    390,
  );
});
test("hides library filters from accounts without library read permissions", async ({
  page,
}) => {
  await page.route("**/api/auth/me", (route) =>
    route.fulfill({
      json: {
        id: "reader",
        username: "reader",
        role: "USER",
        permissions: ["CATALOG_READ"],
        mustChangePassword: false,
      },
    }),
  );
  await page.goto("/catalog");
  await page.locator(".catalog-search summary").click();
  await expect(
    page.getByRole("group", { name: "Torrent", exact: true }),
  ).toHaveCount(0);
  await expect(
    page.getByRole("group", { name: "Calibre", exact: true }),
  ).toHaveCount(0);
});

test("Torrent states allow multiple values, persist and clear on absence", async ({page}) => {
  await page.goto("/catalog");
  await page.locator(".catalog-search summary").click();
  const group=page.getByRole("group", {name:"Torrent", exact:true});
  const states=group.getByRole("textbox", {name:"Estado", exact:true});
  await expect(states).toHaveValue("");
  await states.click();
  await page.getByRole("option", {name:"Descargado",exact:true}).click();
  await states.click();
  await page.getByRole("option", {name:"En cola",exact:true}).click();
  await expect(group.getByRole("textbox",{name:"Presencia",exact:true})).toHaveValue("Presentes");
  await page.keyboard.press("Escape");
  const request=page.waitForRequest(r=>r.url().includes("/api/catalog/books?") && new URL(r.url()).searchParams.getAll("torrentStatus").length===2);
  await page.getByRole("button",{name:"Buscar",exact:true}).click();
  expect(new URL((await request).url()).searchParams.getAll("torrentStatus")).toEqual(["DOWNLOADED","QUEUED"]);
  await page.reload();
  await page.locator(".catalog-search summary").click();
  await expect(group.getByText("Descargado",{exact:true})).toBeVisible();
  await expect(group.getByText("En cola",{exact:true})).toBeVisible();
  await group.getByRole("textbox",{name:"Presencia",exact:true}).click();
  await page.getByRole("option",{name:"Ausentes",exact:true}).click();
  await expect(states).toBeDisabled();
  await expect(group.getByText("Descargado",{exact:true})).toHaveCount(0);
  const absent=page.waitForRequest(r=>r.url().includes("/api/catalog/books?") && new URL(r.url()).searchParams.get("torrentPresence")==="ABSENT");
  await page.getByRole("button",{name:"Buscar",exact:true}).click();
  expect(new URL((await absent).url()).searchParams.has("torrentStatus")).toBe(false);
});
