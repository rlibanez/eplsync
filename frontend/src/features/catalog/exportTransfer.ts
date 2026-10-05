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
