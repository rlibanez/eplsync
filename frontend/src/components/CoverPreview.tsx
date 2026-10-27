import { Modal } from "@mantine/core";
import { useTranslation } from "react-i18next";

export function CoverPreview({
  source,
  label,
  opened,
  onClose,
  onError,
}: {
  source?: string;
  label: string;
  opened: boolean;
  onClose: () => void;
  onError: () => void;
}) {
  const { t } = useTranslation();
  return (
    <Modal
      opened={opened && Boolean(source)}
      onClose={onClose}
      fullScreen
      zIndex={1000}
      title={label}
      className="cover-preview-modal"
      closeButtonProps={{ "aria-label": t("common.close") }}
      styles={{
        content: { background: "rgba(12, 12, 12, 0.98)", color: "white" },
        header: { background: "transparent", color: "white" },
        body: {
          padding: 16,
          height: "calc(100dvh - 64px)",
          display: "flex",
          alignItems: "center",
          justifyContent: "center",
          cursor: "zoom-out",
        },
      }}
    >
      <div className="cover-preview-expanded" onClick={onClose}>
        {source && (
          <img
            src={source}
            alt={label}
            referrerPolicy="no-referrer"
            onError={onError}
          />
        )}
      </div>
    </Modal>
  );
}
