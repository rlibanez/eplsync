import { Button, Stack } from "@mantine/core";
import { Download } from "lucide-react";
import { useState } from "react";
import { useMutation } from "@tanstack/react-query";
import { useTranslation } from "react-i18next";
import { AppModal } from "../../components/AppModal";
import { Loading, Failure } from "../../components/Feedback";
import { get } from "../../api/catalog";
import { useAuth } from "../auth/Auth";

type File = { index: number; name: string; size: number };
export function EpubDownload({ id }: { id: string }) {
  const { can } = useAuth();
  const { t } = useTranslation();
  const [opened, setOpened] = useState(false);
  const files = useMutation({
    mutationFn: () =>
      get<File[]>(`/torrent/downloads/${encodeURIComponent(id)}/epubs`),
    onSuccess: (available) => {
      if (available.length === 1) {
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
      <Button
        variant="subtle"
        title={t("epubDownload.button")}
        size="compact-xs"
        loading={files.isPending && !opened}
        leftSection={<Download size={14} />}
        onClick={() => {
          files.mutate();
        }}
      >
        {t("epubDownload.button")}
      </Button>
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
