/** Clipboard API requires HTTPS; the local HTTP deployment still needs a user-initiated copy. */
export async function copyDetails(text: string) {
  try {
    if (navigator.clipboard) {
      await navigator.clipboard.writeText(text);
      return;
    }
  } catch {
    // Some browsers expose Clipboard but deny permission; try the synchronous fallback.
  }
  const focused =
    document.activeElement instanceof HTMLElement
      ? document.activeElement
      : null;
  const input = document.createElement("textarea");
  input.value = text;
  input.readOnly = true;
  input.style.cssText =
    "position:fixed;top:0;left:0;width:1px;height:1px;opacity:0";
  document.body.append(input);
  try {
    input.select();
    if (!document.execCommand("copy")) throw new Error("Clipboard unavailable");
  } finally {
    input.remove();
    focused?.focus({ preventScroll: true });
  }
}
