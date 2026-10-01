import { AppModal as Modal, ModalActions } from "../../components/AppModal";
import { useState } from "react";
import { BookOpen, Download, Link2 } from "lucide-react";
import {
  Alert,
  Badge,
  Button,
  Group,
  Paper,
  Stack,
  Text,
  TextInput,
} from "@mantine/core";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { useTranslation } from "react-i18next";
import { get } from "../../api/catalog";
import { ActionFailure, post } from "./shared";

export function inferTorrentIdentity(name: string) {
  const ids = [...name.matchAll(/\[(\d+)\]/g)];
  const revisions = [...name.matchAll(/\(r(\d+(?:\.\d+)?)\)/g)];
  return {
    eplId: ids.length === 1 ? ids[0][1] : "",
    revision: revisions.length === 1 ? revisions[0][1] : "",
  };
}

export function LinkTorrent({
  torrent,
  clientInstanceId,
  onClose,
  onLinked,
}: {
  torrent: { hash: string; name: string | null };
  clientInstanceId: string;
  onClose: () => void;
  onLinked: () => void;
}) {
  const { t } = useTranslation();
  const cache = useQueryClient();
  const inferred = inferTorrentIdentity(torrent.name || "");
  const [eplId, setEplId] = useState(inferred.eplId);
  const [revision, setRevision] = useState(inferred.revision);
  const validId =
    /^[1-9]\d*$/.test(eplId) && Number.isSafeInteger(Number(eplId));
  const validRevision =
    /^\d+(\.\d+)?$/.test(revision) &&
    Number(revision) > 0 &&
    Number.isFinite(Number(revision));
  const book = useQuery({
    queryKey: ["link-book", eplId],
    enabled: validId,
    retry: false,
    queryFn: ({ signal }) =>
      get<{ title: string; author: string; revision: number }>(
        `/catalog/books/${eplId}`,
        signal,
      ),
  });
  const link = useMutation({
    mutationFn: () =>
      post("/torrent/downloads/link", {
        clientInstanceId,
        hash: torrent.hash,
        eplId: Number(eplId),
        revision: Number(revision),
      }),
    onSuccess: () => {
      for (const key of ["catalog", "book", "downloads", "download-summary"])
        void cache.invalidateQueries({ queryKey: [key] });
      onLinked();
      onClose();
    },
  });
  return (
    <Modal
      icon={Link2}
      opened
      onClose={() => {
        if (!link.isPending) onClose();
      }}
      size="lg"
      title={t("torrentLink.title")}
      closeOnClickOutside={!link.isPending}
      closeOnEscape={!link.isPending}
      withCloseButton={!link.isPending}
    >
      <form
        onSubmit={(event) => {
          event.preventDefault();
          if (validId && validRevision && book.data && !link.isPending)
            link.mutate();
        }}
      >
        <Stack gap="lg">
          <Paper withBorder radius="md" p="md">
            <Stack gap="sm">
              <Group gap="xs">
                <Download size={18} aria-hidden />
                <Text fw={700} size="sm" c="dimmed">
                  {t("torrentLink.source")}
                </Text>
              </Group>
              <Text fw={600} size="md" style={{ overflowWrap: "anywhere" }}>
                {torrent.name || t("torrentLink.unnamed")}
              </Text>
              <div>
                <Text size="xs" c="dimmed">
                  Hash
                </Text>
                <Text
                  size="xs"
                  ff="monospace"
                  c="dimmed"
                  style={{ overflowWrap: "anywhere" }}
                >
                  {torrent.hash}
                </Text>
              </div>
              <TextInput
                label={t("torrentLink.revision")}
                required
                value={revision}
                disabled={link.isPending}
                onChange={(e) => setRevision(e.currentTarget.value)}
              />
            </Stack>
          </Paper>
          <Paper
            withBorder
            radius="md"
            p="md"
            style={{ borderColor: "var(--mantine-primary-color-filled)" }}
          >
            <Stack gap="sm">
              <Group gap="xs" c="var(--mantine-primary-color-light-color)">
                <BookOpen size={18} aria-hidden />
                <Text fw={700} size="sm">
                  {t("torrentLink.destination")}
                </Text>
              </Group>
              <TextInput
                label="EPL ID"
                required
                value={eplId}
                disabled={link.isPending}
                onChange={(e) => setEplId(e.currentTarget.value)}
              />
              {book.data && (
                <Stack gap={4} aria-live="polite">
                  <Text fw={700} size="lg">
                    {book.data.title}
                  </Text>
                  <Text c="dimmed" size="sm">
                    {book.data.author}
                  </Text>
                  <Badge
                    variant="light"
                    radius="sm"
                    size="lg"
                    mt="xs"
                    style={{
                      height: "auto",
                      whiteSpace: "normal",
                      textTransform: "none",
                    }}
                  >
                    {t("torrentLink.currentRevision", {
                      revision: book.data.revision,
                    })}
                  </Badge>
                </Stack>
              )}
              {book.isFetching && (
                <Text size="sm" c="dimmed">
                  {t("torrentLink.loading")}
                </Text>
              )}
              {book.isError && (
                <Alert color="red">{t("torrentLink.lookupError")}</Alert>
              )}
            </Stack>
          </Paper>
          <Text>{t("torrentLink.help")}</Text>
          <ActionFailure error={link.error} />
          <ModalActions>
            <Button
              variant="default"
              disabled={link.isPending}
              onClick={onClose}
            >
              {t("torrentLink.cancel")}
            </Button>
            <Button
              type="submit"
              loading={link.isPending}
              disabled={!validId || !validRevision || !book.data}
            >
              {t("torrentLink.link")}
            </Button>
          </ModalActions>
        </Stack>
      </form>
    </Modal>
  );
}
