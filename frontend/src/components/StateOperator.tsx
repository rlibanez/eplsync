import { ActionIcon, Tooltip } from "@mantine/core";
import { useTranslation } from "react-i18next";

function StateOperator({
  excluded,
  onToggle,
  disabled,
  text = false,
}: {
  excluded: boolean;
  onToggle: () => void;
  disabled?: boolean;
  text?: boolean;
}) {
  const { t } = useTranslation();
  const label = t(
    text
      ? excluded
        ? "filters.excludesText"
        : "filters.containsText"
      : excluded
        ? "filters.excludeSelected"
        : "filters.includeSelected",
  );
  return (
    <Tooltip label={label}>
      <ActionIcon
        type="button"
        variant="subtle"
        size="sm"
        color={excluded ? "orange" : undefined}
        aria-label={label}
        aria-pressed={excluded}
        disabled={disabled}
        onClick={onToggle}
      >
        {excluded ? "≠" : "="}
      </ActionIcon>
    </Tooltip>
  );
}
export function stateOperatorProps(
  excluded: boolean,
  onToggle: () => void,
  disabled = false,
  text = false,
) {
  return {
    leftSectionPointerEvents: "auto" as const,
    leftSection: (
      <StateOperator
        excluded={excluded}
        onToggle={onToggle}
        disabled={disabled}
        text={text}
      />
    ),
  };
}

export const textOperatorProps = (
  excluded: boolean,
  onToggle: () => void,
  disabled = false,
) => stateOperatorProps(excluded, onToggle, disabled, true);
