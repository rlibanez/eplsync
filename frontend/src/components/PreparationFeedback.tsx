import { Progress, Stack, Text } from "@mantine/core";

export function PreparationFeedback({
  title,
  phase,
  processed = 0,
  total = 0,
  progressLabel,
  count,
  message,
  elapsed,
}: {
  title: string;
  phase: string;
  processed?: number;
  total?: number;
  progressLabel: string;
  count?: string;
  message?: string | null;
  elapsed: string;
}) {
  return (
    <Stack gap="xs" role="status" aria-live="polite">
      <Text fw={600}>{title}</Text>
      <Text>{phase}</Text>
      {total > 0 && (
        <Progress
          value={Math.min(100, (processed * 100) / total)}
          animated
          aria-label={progressLabel}
        />
      )}
      {count && <Text size="sm">{count}</Text>}
      {message && <Text size="sm">{message}</Text>}
      <Text size="sm" c="dimmed">
        {elapsed}
      </Text>
    </Stack>
  );
}
