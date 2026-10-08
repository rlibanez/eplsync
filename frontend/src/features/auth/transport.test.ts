import { afterEach, beforeEach, expect, it, vi } from "vitest";
import { resetCsrf, secureFetch } from "./transport";
vi.mock("../../locales/i18n", () => ({
  default: { t: () => "The session has changed. Try again." },
}));
const token = (value: string) =>
  Response.json({ token: value, headerName: "X-CSRF-TOKEN" });
let fetchMock: ReturnType<typeof vi.fn>;
beforeEach(() => {
  resetCsrf();
  fetchMock = vi.fn();
  vi.stubGlobal("fetch", fetchMock);
  vi.stubGlobal("window", new EventTarget());
});
afterEach(() => vi.unstubAllGlobals());
it("renews tokens after invalidation and preserves multipart request data", async () => {
  fetchMock
    .mockResolvedValueOnce(token("old"))
    .mockResolvedValueOnce(new Response())
    .mockResolvedValueOnce(token("new"))
    .mockResolvedValueOnce(new Response());
  const body = new FormData();
  body.set("file", "example");
  await secureFetch("/api/catalog/import/run", { method: "POST", body });
  resetCsrf();
  await secureFetch("/api/catalog/import/run", { method: "POST", body });
  expect(fetchMock.mock.calls[1][1].headers.get("X-CSRF-TOKEN")).toBe("old");
  expect(fetchMock.mock.calls[3][1].headers.get("X-CSRF-TOKEN")).toBe("new");
  expect(fetchMock.mock.calls[3][1].body).toBe(body);
});
it("returns a CSRF rejection without replaying a destructive request", async () => {
  const changed = vi.fn();
  window.addEventListener("eplsync-auth-session-changed", changed);
  fetchMock
    .mockResolvedValueOnce(token("old"))
    .mockResolvedValueOnce(
      Response.json({ code: "CSRF_INVALID" }, { status: 403 }),
    );
  const response = await secureFetch("/api/catalog/import/reset", {
    method: "POST",
    body: "destructive",
  });
  expect(response.status).toBe(403);
  expect((await response.json()).details).toContain("session has changed");
  expect(fetchMock).toHaveBeenCalledTimes(2);
  expect(changed).toHaveBeenCalledOnce();
  fetchMock
    .mockResolvedValueOnce(token("new"))
    .mockResolvedValueOnce(new Response());
  await secureFetch("/api/catalog/import/reset", { method: "POST" });
  expect(fetchMock.mock.calls[3][1].headers.get("X-CSRF-TOKEN")).toBe("new");
});
it("does not treat a permissions denial as a CSRF failure", async () => {
  const changed = vi.fn();
  window.addEventListener("eplsync-auth-session-changed", changed);
  fetchMock
    .mockResolvedValueOnce(token("valid"))
    .mockResolvedValueOnce(
      Response.json({ code: "ACCESS_DENIED" }, { status: 403 }),
    )
    .mockResolvedValueOnce(new Response());
  await secureFetch("/api/settings", { method: "PUT" });
  await secureFetch("/api/settings", { method: "PUT" });
  expect(fetchMock).toHaveBeenCalledTimes(3);
  expect(changed).not.toHaveBeenCalled();
});
it("cancels a prepared mutation if its pending token belongs to an old session", async () => {
  let resolve!: (value: Response) => void;
  fetchMock.mockReturnValueOnce(
    new Promise<Response>((r) => {
      resolve = r;
    }),
  );
  const old = secureFetch("/api/delete", { method: "DELETE" });
  const rejected = expect(old).rejects.toThrow("session has changed");
  resetCsrf();
  fetchMock
    .mockResolvedValueOnce(token("new"))
    .mockResolvedValueOnce(new Response());
  await secureFetch("/api/settings", { method: "PUT" });
  resolve(token("old"));
  await rejected;
  expect(
    fetchMock.mock.calls.filter(([url]) => url === "/api/delete"),
  ).toHaveLength(0);
});
it("an old rejected token request cannot evict the new session's token", async () => {
  let reject!: (reason: Error) => void;
  fetchMock.mockReturnValueOnce(
    new Promise<Response>((_, r) => {
      reject = r;
    }),
  );
  const old = secureFetch("/api/delete", { method: "DELETE" });
  const rejected = expect(old).rejects.toThrow("offline");
  resetCsrf();
  fetchMock
    .mockResolvedValueOnce(token("new"))
    .mockResolvedValueOnce(new Response());
  await secureFetch("/api/settings", { method: "PUT" });
  reject(new Error("offline"));
  await rejected;
  fetchMock.mockResolvedValueOnce(new Response());
  await secureFetch("/api/settings", { method: "PUT" });
  expect(
    fetchMock.mock.calls.filter(([url]) => url === "/api/auth/csrf"),
  ).toHaveLength(2);
  expect(fetchMock.mock.calls[3][1].headers.get("X-CSRF-TOKEN")).toBe("new");
});
