/** A shared, cancellable queue for Home reads, including future sections. */
class ReadQueue {
  private tail: Promise<void> = Promise.resolve();
  run<T>(read: () => Promise<T>, signal: AbortSignal): Promise<T> {
    const result = this.tail.then(() => {
      signal.throwIfAborted();
      return read();
    });
    this.tail = result.then(
      () => undefined,
      () => undefined,
    );
    return result;
  }
}
export const homeReads = new ReadQueue();
