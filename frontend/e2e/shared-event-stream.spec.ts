import { test, expect } from "@playwright/test";
import { createServer, type ServerResponse } from "node:http";
import { readFileSync } from "node:fs";
import ts from "typescript";
import { networkInterfaces } from "node:os";

for (const mode of [
  "locks",
  "worker-failure",
  "worker",
  "lan-worker",
] as const) {
  test(`HTTP/1.1 ${mode}: ten tabs, closure and session isolation`, async ({
    context,
  }) => {
    if (mode === "locks")
      await context.addInitScript(() => {
        Object.assign(window, { SharedWorker: undefined });
      });
    if (mode === "worker-failure")
      await context.addInitScript(() => {
        Object.assign(window, {
          SharedWorker: class {
            constructor() {
              throw new Error("Worker blocked");
            }
          },
        });
      });
    const clients = new Set<ServerResponse>();
    let opened = 0;
    let maximum = 0;
    const module = ts.transpileModule(
      readFileSync("src/features/events/sharedEventStream.ts", "utf8").replace(
        'import { sessionRevision } from "../auth/sessionChanges";',
        'function sessionRevision() { return localStorage.getItem("eplsync-session-changed") ?? "initial"; }',
      ),
      {
        compilerOptions: {
          target: ts.ScriptTarget.ES2022,
          module: ts.ModuleKind.ESNext,
        },
      },
    ).outputText;
    const workerModule = ts.transpileModule(
      readFileSync("src/features/events/eventStream.worker.ts", "utf8"),
      {
        compilerOptions: {
          target: ts.ScriptTarget.ES2022,
          module: ts.ModuleKind.ESNext,
        },
      },
    ).outputText;
    const server = createServer((req, res) => {
      if (req.url === "/api/events/stream") {
        res.writeHead(200, {
          "Content-Type": "text/event-stream",
          "Cache-Control": "no-cache",
        });
        clients.add(res);
        opened++;
        maximum = Math.max(maximum, clients.size);
        res.write('event: ready\ndata: {"cursor":0}\n\n');
        const heartbeat = setInterval(() => res.write(": keepalive\n\n"), 100);
        res.on("close", () => {
          clearInterval(heartbeat);
          clients.delete(res);
        });
      } else if (req.url === "/stream.js") {
        res.writeHead(200, { "Content-Type": "text/javascript" });
        res.end(module);
      } else if (req.url === "/eventStream.worker.ts") {
        res.writeHead(200, { "Content-Type": "text/javascript" });
        res.end(workerModule);
      } else if (req.url === "/read") res.end("loaded");
      else
        res.end(
          '<script type="module">import {subscribeEventStream} from "/stream.js"; window.messages=[]; window.start=(account)=> {window.stop=subscribeEventStream(account,m=>window.messages.push(m));}; window.start("user-a");</script>',
        );
    });
    const host =
      mode === "lan-worker"
        ? Object.values(networkInterfaces())
            .flat()
            .find((item) => item?.family === "IPv4" && !item.internal)?.address
        : "127.0.0.1";
    expect(host).toBeTruthy();
    await new Promise<void>((resolve) => server.listen(0, host, resolve));
    const address = server.address() as { port: number };
    const url = `http://${host}:${address.port}`;
    const pages = [];
    try {
      for (let i = 0; i < 10; i++) {
        const page = await context.newPage();
        pages.push(page);
        await page.goto(url);
        await expect
          .poll(() => page.evaluate(() => (window as any).messages.length))
          .toBeGreaterThan(0);
      }
      if (mode === "lan-worker") {
        expect(await pages[0].evaluate(() => isSecureContext)).toBe(false);
        expect(await pages[0].evaluate(() => typeof navigator.locks)).toBe(
          "undefined",
        );
      }
      expect(clients.size).toBe(1);
      expect(maximum).toBe(1);
      for (const client of clients)
        client.write('event: event\ndata: {"id":1}\n\n');
      for (const page of pages) {
        await expect
          .poll(() =>
            page.evaluate(() =>
              (window as any).messages.some((m: any) => m.type === "event"),
            ),
          )
          .toBe(true);
      }
      // Real fetches on the same HTTP/1.1 origin, while the permanent stream remains open.
      const results = await Promise.all(
        pages.map((page) =>
          page.evaluate(async () => {
            const response = await fetch("/read", {
              signal: AbortSignal.timeout(3000),
            });
            return response.text();
          }),
        ),
      );
      expect(results).toEqual(Array(10).fill("loaded"));
      await pages[0].close();
      await expect.poll(() => clients.size).toBe(1);
      if (mode === "worker" || mode === "lan-worker") {
        expect(opened).toBe(1);
        await pages[1].reload();
        await expect
          .poll(() => pages[1].evaluate(() => (window as any).messages.length))
          .toBeGreaterThan(0);
        expect(opened).toBe(1);
      } else await expect.poll(() => opened).toBe(2);
      for (const client of clients)
        client.write('event: event\ndata: {"id":2}\n\n');
      for (const page of pages.slice(1)) {
        await expect
          .poll(() =>
            page.evaluate(() =>
              (window as any).messages.some((m: any) =>
                m.data.includes('"id":2'),
              ),
            ),
          )
          .toBe(true);
      }
      // Change the cookie-session epoch: old subscribers must ignore any remaining events.
      await pages[1].evaluate(() =>
        localStorage.setItem("eplsync-session-changed", "1-new-session"),
      );
      for (const client of clients)
        client.write('event: event\ndata: {"id":3}\n\n');
      for (const page of pages.slice(1))
        await page.evaluate(() => (window as any).stop());
      await expect.poll(() => clients.size).toBe(0);
      for (const page of pages.slice(1)) {
        expect(
          await page.evaluate(() =>
            (window as any).messages.some((m: any) =>
              m.data.includes('"id":3'),
            ),
          ),
        ).toBe(false);
        await page.evaluate(() => {
          (window as any).messages = [];
          (window as any).start("user-b");
        });
      }
      await expect.poll(() => clients.size).toBe(1);
      for (const client of clients)
        client.write('event: event\ndata: {"id":4}\n\n');
      for (const page of pages.slice(1))
        await expect
          .poll(() =>
            page.evaluate(() =>
              (window as any).messages.some((m: any) =>
                m.data.includes('"id":4'),
              ),
            ),
          )
          .toBe(true);
      expect(maximum).toBe(1);
      await pages[1].evaluate(() =>
        localStorage.setItem("eplsync-session-changed", "2-logged-out"),
      );
      for (const page of pages.slice(1))
        await page.evaluate(() => (window as any).stop());
      await expect.poll(() => clients.size).toBe(0);
      // Browsers lacking Web Locks use finite polling, never a permanent stream per tab.
      const fallback = pages[1];
      await fallback.clock.install();
      await fallback.evaluate(() => {
        Object.assign(window, { SharedWorker: undefined });
        Object.defineProperty(navigator, "locks", { value: undefined });
        (window as any).messages = [];
        (window as any).start("user-b");
      });
      await fallback.clock.fastForward(30_000);
      expect(
        await fallback.evaluate(
          () =>
            (window as any).messages.filter((m: any) => m.type === "refresh")
              .length,
        ),
      ).toBe(2);
      expect(clients.size).toBe(0);
      expect(
        await fallback.evaluate(async () => (await fetch("/read")).text()),
      ).toBe("loaded");
    } finally {
      await Promise.all(pages.map((page) => page.close()));
      for (const client of clients) client.end();
      server.closeAllConnections();
      await new Promise<void>((resolve) => server.close(() => resolve()));
    }
  });
}
