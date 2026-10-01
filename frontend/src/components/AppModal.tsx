import { Group, Modal, Text, ThemeIcon, type ModalProps } from "@mantine/core";
import type { LucideIcon } from "lucide-react";
import type { ReactNode } from "react";

export function AppModal({
  title,
  icon: Icon,
  ...props
}: ModalProps & { icon: LucideIcon }) {
  return (
    <Modal
      {...props}
      title={
        <Group gap="sm" wrap="nowrap">
          <ThemeIcon
            variant="light"
            size={30}
            radius="md"
            style={{ flexShrink: 0 }}
          >
            <Icon size={18} aria-hidden="true" />
          </ThemeIcon>
          <Text component="span" fw={600} size="lg">
            {title}
          </Text>
        </Group>
      }
    />
  );
}

export function ModalActions({ children }: { children: ReactNode }) {
  return (
    <Group justify="flex-end" gap="sm" mt="lg">
      {children}
    </Group>
  );
}
