import { expect, it, vi } from "vitest";
import { generateSecretKey } from "./secretKey";

it("uses the cryptographic generator and returns an AES-256 key accepted by the backend", () => {
  const spy = vi.spyOn(crypto, "getRandomValues");
  try {
    const first = generateSecretKey(),
      second = generateSecretKey();
    expect(first).toMatch(/^[A-Za-z0-9+/]{43}=$/);
    expect(atob(first)).toHaveLength(32);
    expect(first).not.toEqual(second);
    expect(spy).toHaveBeenCalledTimes(2);
  } finally {
    spy.mockRestore();
  }
});
