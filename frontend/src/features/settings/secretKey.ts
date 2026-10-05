/** A fresh AES-256 key, encoded in the canonical format accepted by the backend. */
export function generateSecretKey(): string {
  const bytes = crypto.getRandomValues(new Uint8Array(32));
  return btoa(String.fromCharCode(...bytes));
}
