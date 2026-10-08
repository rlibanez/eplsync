import { beforeEach, afterEach, it, expect, vi } from "vitest";
let state: typeof import("./eventReadState");
let values: Map<string, string>;
beforeEach(async () => {
  vi.resetModules();
  values = new Map();
  vi.stubGlobal("localStorage", {
    getItem: (key: string) => values.get(key) ?? null,
    setItem: (key: string, value: string) => values.set(key, value),
  });
  vi.stubGlobal("window", new EventTarget());
  state = await import("./eventReadState");
});
afterEach(() => vi.unstubAllGlobals());
it("keeps cursors and individual reads independent when switching accounts", () => {
  state.markOperationRead("alice", 5);
  state.markEventsRead("bob", 8);
  state.markOperationRead("bob", 12);
  expect(state.eventReadCursor("alice")).toBe(0);
  expect(state.readItems(state.eventReadItemsSnapshot("alice"))).toEqual([5]);
  expect(state.eventReadCursor("bob")).toBe(8);
  expect(state.readItems(state.eventReadItemsSnapshot("bob"))).toEqual([12]);
  state.markEventsRead("alice", 6);
  expect(state.readItems(state.eventReadItemsSnapshot("alice"))).toEqual([]);
  expect(state.eventReadCursor("bob")).toBe(8);
  expect(state.readItems(state.eventReadItemsSnapshot("bob"))).toEqual([12]);
});
it("ignores legacy shared marks and anonymous writes", () => {
  values.set("eplsync.events.lastRead", "100");
  values.set("eplsync.events.readItems", "[101]");
  expect(state.eventReadCursor("alice")).toBe(0);
  expect(state.eventReadItemsSnapshot("alice")).toBe("[]");
  state.markEventsRead(undefined, 200);
  state.markOperationRead(undefined, 201);
  state.resetEventReadState(undefined);
  expect(values.size).toBe(2);
});
it("isolates fallback state when browser storage is unavailable", () => {
  vi.stubGlobal("localStorage", {
    getItem: () => {
      throw new Error("unavailable");
    },
    setItem: () => {
      throw new Error("unavailable");
    },
  });
  state.markEventsRead("alice", 4);
  state.markOperationRead("alice", 7);
  expect(state.eventReadCursor("bob")).toBe(0);
  expect(state.eventReadItemsSnapshot("bob")).toBe("[]");
  expect(state.eventReadCursor("alice")).toBe(4);
  expect(state.readItems(state.eventReadItemsSnapshot("alice"))).toEqual([7]);
});
it("resets only the account whose database cursor has changed", () => {
  for (const id of ["alice", "bob"]) {
    state.markEventsRead(id, 10);
    state.markOperationRead(id, 20);
  }
  state.resetEventReadState("alice");
  expect(state.eventReadCursor("alice")).toBe(0);
  expect(state.eventReadItemsSnapshot("alice")).toBe("[]");
  expect(state.eventReadCursor("bob")).toBe(10);
  expect(state.readItems(state.eventReadItemsSnapshot("bob"))).toEqual([20]);
});
it("rejects invalid values and sees changes written by another tab", () => {
  values.set("eplsync.events.alice.lastRead", "-1");
  values.set("eplsync.events.alice.readItems", '[1,-1,1.2,"2"]');
  expect(state.eventReadCursor("alice")).toBe(0);
  expect(state.readItems(state.eventReadItemsSnapshot("alice"))).toEqual([1]);
  state.markEventsRead("alice", NaN);
  state.markOperationRead("alice", -1);
  state.markOperationRead("alice", 1);
  expect(values.get("eplsync.events.alice.readItems")).toBe('[1,-1,1.2,"2"]');
  values.set("eplsync.events.alice.lastRead", "3");
  expect(state.eventReadCursor("alice")).toBe(3);
});
