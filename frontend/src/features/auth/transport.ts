import i18n from "../../locales/i18n";
let csrf: Promise<{ token: string; headerName: string }> | undefined;
let csrfGeneration = 0;
export function resetCsrf() {
  ++csrfGeneration;
  csrf = undefined;
}
export async function secureFetch(
  input: string,
  init: RequestInit = {},
): Promise<Response> {
  const requestGeneration = csrfGeneration;
  const method = (init.method ?? "GET").toUpperCase();
  const headers = new Headers(init.headers);
  if (!["GET", "HEAD", "OPTIONS"].includes(method)) {
    if (!csrf) {
      const pending = fetch("/api/auth/csrf", {
        credentials: "same-origin",
        cache: "no-store",
      })
        .then(async (response) => {
          if (!response.ok)
            throw new Error("Unable to initialize CSRF protection");
          return response.json();
        })
        .catch((error) => {
          if (csrf === pending) csrf = undefined;
          throw error;
        });
      csrf = pending;
    }
    const token = await csrf;
    // A session change while preparing a mutation requires a new explicit user action.
    if (requestGeneration !== csrfGeneration)
      throw new Error(i18n.t("auth.sessionChanged"));
    headers.set(token.headerName, token.token);
  }
  const response = await fetch(input, {
    ...init,
    headers,
    credentials: "same-origin",
  });
  if (response.status === 403 && !["GET", "HEAD", "OPTIONS"].includes(method)) {
    const error = await response
      .clone()
      .json()
      .catch(() => null);
    if (error?.code === "CSRF_INVALID") {
      if (requestGeneration === csrfGeneration) {
        resetCsrf();
        window.dispatchEvent(new Event("eplsync-auth-session-changed"));
      }
      // Return the rejection; never replay this request, including destructive actions.
      const headers = new Headers(response.headers);
      headers.delete("content-length");
      headers.delete("content-encoding");
      return new Response(
        JSON.stringify({ ...error, details: i18n.t("auth.sessionChanged") }),
        {
          status: response.status,
          statusText: response.statusText,
          headers,
        },
      );
    }
  }
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
