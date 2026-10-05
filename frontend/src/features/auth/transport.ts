let csrf: Promise<{ token: string; headerName: string }> | undefined;
export function resetCsrf() {
  csrf = undefined;
}
export async function secureFetch(
  input: string,
  init: RequestInit = {},
): Promise<Response> {
  const method = (init.method ?? "GET").toUpperCase();
  const headers = new Headers(init.headers);
  if (!["GET", "HEAD", "OPTIONS"].includes(method)) {
    csrf ??= fetch("/api/auth/csrf", {
      credentials: "same-origin",
      cache: "no-store",
    })
      .then(async (response) => {
        if (!response.ok)
          throw new Error("Unable to initialize CSRF protection");
        return response.json();
      })
      .catch((error) => {
        csrf = undefined;
        throw error;
      });
    const token = await csrf;
    headers.set(token.headerName, token.token);
  }
  const response = await fetch(input, {
    ...init,
    headers,
    credentials: "same-origin",
  });
  if (response.status === 401 && !input.startsWith("/api/auth/"))
    window.dispatchEvent(new Event("eplsync-auth-expired"));
  return response;
}
export async function authRequest<T>(
  path: string,
  method = "GET",
  body?: unknown,
): Promise<T> {
  const response = await secureFetch(`/api${path}`, {
    method,
    headers:
      body === undefined ? undefined : { "Content-Type": "application/json" },
    body: body === undefined ? undefined : JSON.stringify(body),
  });
  const data = await response.json().catch(() => null);
  if (!response.ok)
    throw new Error(data?.details ?? data?.code ?? `HTTP ${response.status}`);
  return data as T;
}
