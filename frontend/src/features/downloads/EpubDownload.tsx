import { TableAction } from "../../components/TableAction";
import { Alert, Button, Stack } from "@mantine/core";
import { Download } from "lucide-react";
import { useState } from "react";
import { useMutation } from "@tanstack/react-query";
import { useTranslation } from "react-i18next";
import { AppModal } from "../../components/AppModal";
import { Loading, Failure } from "../../components/Feedback";
import { get } from "../../api/catalog";
import { useAuth } from "../auth/Auth";

type File = {
  index: number;
  name: string;
  size: number;
  expectedSize?: number;
};
export function EpubDownload({ id }: { id: string }) {
  const { can } = useAuth();
  const { t } = useTranslation();
  const [opened, setOpened] = useState(false);
  const files = useMutation({
    mutationFn: () =>
      get<File[]>(`/torrent/downloads/${encodeURIComponent(id)}/epubs`),
    onSuccess: (available) => {
      if (
        available.length === 1 &&
        (available[0].expectedSize == null ||
          available[0].expectedSize === available[0].size)
      ) {
        setOpened(false);
        const link = document.createElement("a");
        link.href = `/api/torrent/downloads/${encodeURIComponent(id)}/epubs/${available[0].index}`;
        document.body.appendChild(link);
        link.click();
        link.remove();
      } else {
        setOpened(true);
      }
    },
    onError: () => setOpened(true),
  });
  if (!can("DOWNLOADS_FILES_READ")) return null;
  return (
    <>
      <TableAction
        label={t("epubDownload.button")}
        loading={files.isPending && !opened}
        onClick={() => files.mutate()}
      >
        <Download size={16} aria-hidden="true" />
      </TableAction>
      <AppModal
        opened={opened}
        onClose={() => setOpened(false)}
        title={t("epubDownload.title")}
        icon={Download}
      >
        {files.isPending ? (
          <Loading />
        ) : files.isError ? (
          <Failure error={files.error} retry={() => files.mutate()} />
        ) : (
          <Stack gap="sm">
            <p>{t("epubDownload.choose")}</p>
            {files.data
              ?.filter(
                (file) =>
                  file.expectedSize != null && file.expectedSize !== file.size,
              )
              .map((file) => (
                <Alert key={file.index} color="yellow">
                  {file.name}: {t("epubDownload.sizeWarning")}
                </Alert>
              ))}
            {files.data?.map((file) => (
              <Button
                key={file.index}
                component="a"
                variant="light"
                href={`/api/torrent/downloads/${encodeURIComponent(id)}/epubs/${file.index}`}
              >
                {file.name}
              </Button>
            ))}
          </Stack>
        )}
      </AppModal>
    </>
  );
}
