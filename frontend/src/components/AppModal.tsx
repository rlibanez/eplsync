import { Group, Modal, Text, ThemeIcon, type ModalProps } from "@mantine/core";
import type { LucideIcon } from "lucide-react";
import type { ReactNode } from "react";

const widths = { compact: 440, normal: 640, wide: 960 };
export function AppModal({
  title,
  icon: Icon,
  width = "normal",
  children,
  className,
  ...props
}: Omit<ModalProps, "size"> & {
  icon: LucideIcon;
  width?: keyof typeof widths;
}) {
  return (
    <Modal
      centered
      padding={20}
      {...props}
      size={widths[width]}
      className={["app-modal", className].filter(Boolean).join(" ")}
      styles={{
        inner: { overflowY: "auto", alignItems: "flex-start" },
        content: { maxHeight: "none", marginBlock: "auto", flexShrink: 0 },
      }}
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
    >
      <div className="app-modal-body">{children}</div>
    </Modal>
  );
}

export function ModalActions({
  children,
  summary,
}: {
  children: ReactNode;
  summary?: ReactNode;
}) {
  return (
    <Group
      className="app-modal-actions"
      justify={summary ? "space-between" : "flex-end"}
      gap="sm"
    >
      {summary}
      <Group gap="sm" justify="flex-end">
        {children}
      </Group>
    </Group>
  );
}
