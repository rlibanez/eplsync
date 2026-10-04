import { useState } from "react";
import { Tooltip } from "@mantine/core";
export interface SendDefaults {
  start: boolean;
  autoManagement: boolean;
  savePath: string | null;
  rename: { enabled: boolean; pattern: string };
  category: string | null;
  tags: string[];
  concurrency: number;
  batchSize: number;
  interval: string;
  multipleHashes: string;
}
export function OptionLabel({ text, help }: { text: string; help: string }) {
  const [hovered, setHovered] = useState(false);
  const [focused, setFocused] = useState(false);
  return (
    <Tooltip
      opened={hovered || focused}
      label={<span style={{ whiteSpace: "pre-line" }}>{help}</span>}
      multiline
      w={360}
      withArrow
      events={{ hover: true, focus: true, touch: true }}
    >
      <span
        tabIndex={0}
        onMouseEnter={() => setHovered(true)}
        onMouseLeave={() => setHovered(false)}
        onFocus={() => setFocused(true)}
        onBlur={() => setFocused(false)}
        aria-description={help}
        style={{ display: "inline-flex", cursor: "help" }}
      >
        {text}
      </span>
    </Tooltip>
  );
}
