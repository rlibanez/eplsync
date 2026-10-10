import { test, expect } from "./fixtures";

test("about shows build identity, checks stable releases and saves automatic checks", async ({
  page,
}) => {
  let state = {
    automatic: true,
    state: "NOT_CHECKED",
    latestVersion: null as string | null,
    releaseUrl: null as string | null,
    checkedAt: null as string | null,
    checking: false,
  };
  let checks = 0;
  await page.route("**/api/application/updates", (route) =>
    route.fulfill({ json: state }),
  );
  await page.route("**/api/application/updates/check", (route) => {
    expect(route.request().method()).toBe("POST");
    expect(route.request().headers()["x-csrf-token"]).toBe("test-csrf");
    checks++;
    state = {
      ...state,
      state: "AVAILABLE",
      latestVersion: "0.0.2",
      releaseUrl: "https://github.com/rlibanez/eplsync/releases/tag/v0.0.2",
      checkedAt: "2026-10-10T09:00:00Z",
    };
    return route.fulfill({ json: state });
  });
  await page.route("**/api/application/updates/settings", (route) => {
    expect(route.request().method()).toBe("PUT");
    state = { ...state, automatic: route.request().postDataJSON().automatic };
    return route.fulfill({ json: state });
  });
  await page.goto("/settings/about");
  await expect(
    page.getByText("Versión 0.0.1 (0ef2d7b)", { exact: true }),
  ).toBeVisible();
  await expect(
    page.getByRole("link", { name: "Notas de la versión", exact: false }).first(),
  ).toHaveAttribute("href", "https://github.com/rlibanez/eplsync/releases/tag/v0.0.1");
  await page
    .getByRole("button", { name: "Comprobar actualizaciones", exact: true })
    .click();
  await expect(
    page
      .getByRole("link", { name: "Notas de la versión", exact: false })
      .filter({ has: page.locator("svg") })
      .last(),
  ).toHaveAttribute("href", /releases\/tag\/v0.0.2$/);
  expect(checks).toBe(1);
  await page
    .getByRole("checkbox", {
      name: "Comprobar actualizaciones automáticamente",
    })
    .click();
  await expect.poll(() => state.automatic).toBe(false);
  await page.reload();
  await expect(
    page.getByRole("checkbox", {
      name: "Comprobar actualizaciones automáticamente",
    }),
  ).not.toBeChecked();
});

test("regular accounts can see installed version without requesting update administration", async ({
  page,
}) => {
  await page.route("**/api/auth/me", (route) =>
    route.fulfill({
      json: {
        id: "reader",
        username: "reader",
        email: "reader@example.org",
        role: "USER",
        mustChangePassword: false,
        permissions: [],
      },
    }),
  );
  const calls: string[] = [];
  await page.route("**/api/application/updates**", (route) => {
    calls.push(route.request().url());
    return route.fulfill({ status: 403, json: {} });
  });
  await page.goto("/settings/about");
  await expect(
    page.getByText("Versión 0.0.1 (0ef2d7b)", { exact: true }),
  ).toBeVisible();
  await expect(
    page.getByRole("button", {
      name: "Comprobar actualizaciones",
      exact: true,
    }),
  ).toHaveCount(0);
  expect(calls).toEqual([]);
});
