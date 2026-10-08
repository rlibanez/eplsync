import { test, expect } from "@playwright/test";

test("another tab renews the token even when the account stays the same", async ({
  context,
  page,
}) => {
  let epoch = 1;
  let stale = 0;
  let saves = 0;
  let reads = 0;
  const account = {
    id: "reader",
    username: "reader",
    email: "reader@example.org",
    role: "USER",
    mustChangePassword: false,
    permissions: [],
  };
  await context.route("**/api/**", async (route) => {
    const path = new URL(route.request().url()).pathname;
    if (path === "/api/auth/me") {
      reads++;
      return route.fulfill({ json: account });
    }
    if (path === "/api/auth/csrf")
      return route.fulfill({
        json: { token: `token-${epoch}`, headerName: "X-CSRF-TOKEN" },
      });
    if (path === "/api/auth/email") {
      if (route.request().headers()["x-csrf-token"] !== `token-${epoch}`) {
        stale++;
        return route.fulfill({ status: 403, json: { code: "CSRF_INVALID" } });
      }
      saves++;
    }
    return route.fulfill({ json: {} });
  });
  await page.goto("/settings/account");
  const save = page.getByRole("button", { name: "Guardar", exact: true });
  await save.click();
  await expect.poll(() => saves).toBe(1);
  const other = await context.newPage();
  await other.goto("/settings/account");
  await expect(
    other.getByLabel("Correo electrónico", { exact: true }),
  ).toBeVisible();
  const before = reads;
  epoch++;
  await other.evaluate(() => {
    const channel = new BroadcastChannel("eplsync-session-changed");
    channel.postMessage("eplsync-session-changed");
    channel.close();
  });
  await expect.poll(() => reads).toBeGreaterThan(before);
  await save.click();
  await expect.poll(() => saves).toBe(2);
  expect(stale).toBe(0);
});
