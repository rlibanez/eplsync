import { useState, type ReactElement } from "react";
import { Tooltip } from "@mantine/core";

export function ValidationTip({
  message,
  success = false,
  children,
}: {
  message?: string;
  success?: boolean;
  children: ReactElement;
}) {
  const [focused, setFocused] = useState(false);
  const [hovered, setHovered] = useState(false);
  return (
    <Tooltip
      label={message}
      opened={!!message && (focused || hovered)}
      transitionProps={{ duration: 0 }}
      color={success ? "green" : "red"}
      position="bottom-start"
      multiline
      w={280}
      withArrow
      events={{ hover: false, focus: false, touch: false }}
    >
      <div
        onFocusCapture={() => setFocused(true)}
        onBlurCapture={() => setFocused(false)}
        onMouseEnter={() => setHovered(true)}
        onMouseLeave={() => setHovered(false)}
      >
        {children}
      </div>
    </Tooltip>
  );
}
