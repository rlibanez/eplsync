export function readyPreparation(result: unknown) {
  return {
    id: "mock-preparation",
    kind: "PREPARE",
    state: "COMPLETED",
    phase: "READY",
    processed: 1,
    total: 1,
    startedAt: Date.now(),
    message: null,
    result,
  };
}
