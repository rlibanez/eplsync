import { test, expect } from "./fixtures";

test("account appearance replaces browser cache and persists automatic edits", async ({
  page,
}) => {
  let saved = { userId: "test-admin", scheme: "light", palette: "violet" };
  const writes: unknown[] = [];
  await page.route("**/api/auth/appearance", async (route) => {
    if (route.request().method() === "PUT") {
      const body = route.request().postDataJSON();
      writes.push(body);
      saved = {
        userId: body.userId,
        scheme: body.scheme,
        palette: body.palette,
      };
    }
    await route.fulfill({ json: saved });
  });
  await page.addInitScript(() => {
    localStorage.setItem("eplsync:scheme", "dark");
    localStorage.setItem("eplsync:palette", "orange");
  });
  await page.goto("/settings/general");
  await expect(
    page.getByRole("radio", { name: "Claro", exact: true }),
  ).toBeChecked();
  await expect(page.locator("html")).toHaveAttribute("data-palette", "violet");
  expect(writes).toHaveLength(0);
  await page.getByRole("radio", { name: "Azul", exact: true }).check();
  await expect.poll(() => saved.palette).toBe("blue");
  await page.getByRole("radio", { name: "Oscuro", exact: true }).check();
  await expect.poll(() => saved.scheme).toBe("dark");
  await page.reload();
  await expect(page.locator("html")).toHaveAttribute("data-palette", "blue");
  await expect(
    page.getByRole("radio", { name: "Oscuro", exact: true }),
  ).toBeChecked();
});

test("first login adopts local appearance only when account has no saved preference", async ({
  page,
}) => {
  let saved: { userId: string; scheme: string | null; palette: string | null } =
    { userId: "test-admin", scheme: null, palette: null };
  const writes: { initialize: boolean; scheme: string; palette: string }[] = [];
  await page.route("**/api/auth/appearance", async (route) => {
    if (route.request().method() === "PUT") {
      const body = route.request().postDataJSON();
      writes.push(body);
      saved = {
        userId: body.userId,
        scheme: body.scheme,
        palette: body.palette,
      };
    }
    await route.fulfill({ json: saved });
  });
  await page.addInitScript(() => {
    localStorage.setItem("eplsync:scheme", "light");
    localStorage.setItem("eplsync:palette", "rose");
  });
  await page.goto("/settings/general");
  await expect.poll(() => writes.length).toBe(1);
  expect(writes[0]).toMatchObject({
    initialize: true,
    scheme: "light",
    palette: "rose",
  });
  await page.reload();
  await expect(page.locator("html")).toHaveAttribute("data-palette", "rose");
  expect(writes).toHaveLength(1);
});

test("save failure preserves local appearance and reports it", async ({
  page,
}) => {
  let failing = false;
  await page.route("**/api/auth/appearance", async (route) => {
    if (route.request().method() === "PUT" && failing)
      return route.fulfill({ status: 503, json: {} });
    await route.fulfill({
      json: { userId: "test-admin", scheme: "dark", palette: "teal" },
    });
  });
  await page.goto("/settings/general");
  await expect(
    page.getByRole("radio", { name: "Oscuro", exact: true }),
  ).toBeChecked();
  failing = true;
  await page.getByRole("radio", { name: "Azul", exact: true }).check();
  await expect(
    page.getByText(/No se pudo sincronizar la apariencia/),
  ).toBeVisible();
  await expect(page.locator("html")).toHaveAttribute("data-palette", "blue");
});

test("switching accounts loads the new user's appearance and saves only for that user", async ({
  page,
}) => {
  let id = "first";
  const saved: Record<string, { scheme: string; palette: string }> = {
    first: { scheme: "light", palette: "blue" },
    second: { scheme: "dark", palette: "orange" },
  };
  const writes: string[] = [];
  await page.route("**/api/auth/me", (route) =>
    route.fulfill({
      json: {
        id,
        username: id,
        email: `${id}@example.org`,
        role: "USER",
        mustChangePassword: false,
        permissions: [],
      },
    }),
  );
  await page.route("**/api/auth/appearance", async (route) => {
    if (route.request().method() === "PUT") {
      const body = route.request().postDataJSON();
      writes.push(body.userId);
      saved[body.userId] = { scheme: body.scheme, palette: body.palette };
    }
    await route.fulfill({ json: { userId: id, ...saved[id] } });
  });
  await page.goto("/settings/general");
  await expect(page.locator("html")).toHaveAttribute("data-palette", "blue");
  id = "second";
  await page.evaluate(() =>
    window.dispatchEvent(new Event("eplsync-auth-session-changed")),
  );
  await expect(page.locator("html")).toHaveAttribute("data-palette", "orange");
  await expect(
    page.getByRole("radio", { name: "Oscuro", exact: true }),
  ).toBeChecked();
  await page.getByRole("radio", { name: "Azul", exact: true }).check();
  await expect.poll(() => writes).toEqual(["second"]);
  expect(saved.first).toEqual({ scheme: "light", palette: "blue" });
});
