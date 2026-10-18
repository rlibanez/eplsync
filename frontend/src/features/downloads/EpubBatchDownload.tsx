import { useState } from "react";
import { TableAction } from "../../components/TableAction";
import { AppModal, ModalActions } from "../../components/AppModal";
import { Alert, Button, Stack } from "@mantine/core";
import { Download } from "lucide-react";
import { useMutation } from "@tanstack/react-query";
import { useTranslation } from "react-i18next";
import { Failure } from "../../components/Feedback";
import { post } from "./shared";

export function EpubBatchDownload({
  selected,
  endpoint = "/torrent/downloads/epub-batches",
  inline = false,
  toolbar = true,
}: {
  selected: (string | number)[];
  endpoint?: string;
  inline?: boolean;
  toolbar?: boolean;
}) {
  const { t } = useTranslation();
  const [warning, setWarning] = useState<{
    url: string;
    sizeWarnings: string[];
  } | null>(null);
  const startDownload = (url: string) => {
    const link = document.createElement("a");
    link.href = url;
    document.body.appendChild(link);
    link.click();
    link.remove();
  };
  const batch = useMutation({
    retry: false,
    mutationFn: (ids: (string | number)[]) =>
      post<{ url: string; sizeWarnings?: string[] }>(endpoint, { ids }),
    onSuccess: ({ url, sizeWarnings }) => {
      if (sizeWarnings?.length) setWarning({ url, sizeWarnings });
      else startDownload(url);
    },
  });
  if (!selected.length) return null;
  const button = inline ? (
    <TableAction
      label={t("epubDownload.button")}
      loading={batch.isPending}
      onClick={() => batch.mutate(selected)}
    >
      <Download size={16} aria-hidden="true" />
    </TableAction>
  ) : (
    <Button
      variant="light"
      size="sm"
      leftSection={<Download size={16} />}
      loading={batch.isPending}
      onClick={() => batch.mutate(selected)}
    >
      {t("epubDownload.download")}
    </Button>
  );
  return (
    <>
      {button}
      <AppModal
        opened={warning !== null}
        onClose={() => setWarning(null)}
        title={t("epubDownload.title")}
        icon={Download}
      >
        <Stack>
          <Alert color="yellow">{t("epubDownload.sizeWarning")}</Alert>
          {warning?.sizeWarnings.map((name, index) => (
            <div key={index}>{name}</div>
          ))}
          <ModalActions>
            <Button variant="default" onClick={() => setWarning(null)}>
              {t("common.cancel")}
            </Button>
            <Button
              onClick={() => {
                if (warning) startDownload(warning.url);
                setWarning(null);
              }}
            >
              {t("epubDownload.continueDownload")}
            </Button>
          </ModalActions>
        </Stack>
      </AppModal>
      {batch.isError &&
        batch.variables?.join("|") === selected.join("|") &&
        (inline || !toolbar ? (
          <AppModal
            opened
            onClose={() => batch.reset()}
            title={t("epubDownload.title")}
            icon={Download}
          >
            <Failure error={batch.error} retry={() => batch.mutate(selected)} />
            <ModalActions>
              <Button variant="default" onClick={() => batch.reset()}>
                {t("common.close")}
              </Button>
            </ModalActions>
          </AppModal>
        ) : (
          <Failure error={batch.error} retry={() => batch.mutate(selected)} />
        ))}
    </>
  );
}
