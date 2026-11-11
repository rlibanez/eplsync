import { secureFetch } from "../auth/transport";

/** Apply backpressure: only request the next chunk after the file accepts this one. */
export async function copyExportStream(
  response: Response,
  target: { write(data: Uint8Array): Promise<void> },
  emptyMessage: string,
) {
  if (!response.body) throw new Error(emptyMessage);
  const reader = response.body.getReader();
  let bytes = 0;
  try {
    while (true) {
      const { done, value } = await reader.read();
      if (done) break;
      await target.write(value);
      bytes += value.byteLength;
    }
    if (!bytes) throw new Error(emptyMessage);
  } catch (error) {
    await reader.cancel().catch(() => {});
    throw error;
  } finally {
    reader.releaseLock();
  }
}

interface WritableFile {
  write(data: Uint8Array): Promise<void>;
  close(): Promise<void>;
  abort(): Promise<void>;
}

type PickerWindow = Window & {
  showSaveFilePicker?: (options: { suggestedName: string }) => Promise<{
    createWritable(): Promise<WritableFile>;
  }>;
};

export async function saveCatalogExport(
  endpoint: string,
  filename: string,
  body: unknown,
  errorMessage: string,
  emptyMessage: string,
  accept = "*/*",
) {
  // Open the picker before the network request, while the click has user activation.
  const picker = (window as PickerWindow).showSaveFilePicker;
  const handle = await picker?.call(window, { suggestedName: filename });
  const response = await secureFetch(endpoint, {
    method: "POST",
    headers: { "Content-Type": "application/json", Accept: accept },
    body: JSON.stringify(body),
  });
  if (!response.ok) {
    const error = await response.json().catch(() => undefined);
    throw new Error(
      typeof error?.details === "string" ? error.details : errorMessage,
    );
  }
  if (response.headers.get("Content-Length") === "0")
    throw new Error(emptyMessage);
  if (handle) {
    const writable = await handle.createWritable();
    try {
      await copyExportStream(response, writable, emptyMessage);
      await writable.close();
    } catch (error) {
      await writable.abort().catch(() => {});
      throw error;
    }
    return;
  }
  const blob = await response.blob();
  if (!blob.size) throw new Error(emptyMessage);
  const url = URL.createObjectURL(blob);
  const link = document.createElement("a");
  link.href = url;
  link.download = filename;
  document.body.append(link);
  link.click();
  link.remove();
  setTimeout(() => URL.revokeObjectURL(url), 60_000);
}
