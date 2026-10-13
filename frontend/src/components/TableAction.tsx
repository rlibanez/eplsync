import { ActionIcon, Tooltip } from "@mantine/core";
import type { ReactNode } from "react";

export function TableAction({
  label,
  children,
  href,
  loading,
  disabled,
  onClick,
}: {
  label: string;
  children: ReactNode;
  href?: string;
  loading?: boolean;
  disabled?: boolean;
  onClick?: () => void;
}) {
  return (
    <Tooltip label={label} events={{ hover: true, focus: true, touch: false }}>
      {href ? (
        <ActionIcon
          component="a"
          href={href}
          target="_blank"
          rel="noopener noreferrer"
          variant="subtle"
          size="md"
          aria-label={label}
        >
          {children}
        </ActionIcon>
      ) : (
        <ActionIcon
          variant="subtle"
          size="md"
          aria-label={label}
          loading={loading}
          disabled={disabled}
          onClick={onClick}
        >
          {children}
        </ActionIcon>
      )}
    </Tooltip>
  );
}
