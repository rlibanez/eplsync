import { useState } from "react";
import { ActionIcon, Button, Group, Modal, TextInput } from "@mantine/core";
import { Pencil } from "lucide-react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { useTranslation } from "react-i18next";
import { PageTitle } from "../../components/PageTitle";
import { get } from "../../api/catalog";
import { post } from "../downloads/shared";
import { Failure } from "../../components/Feedback";

export function JobTitle({
  source,
  id,
  title,
  description,
  editable,
}: {
  source: "downloads" | "calibre" | "cleanup";
  id: string;
  title: string;
  description: string;
  editable: boolean;
}) {
  const { t } = useTranslation();
  const cache = useQueryClient();
  const [opened, setOpened] = useState(false);
  const [draft, setDraft] = useState("");
  const path = `/settings/work/${source}/${encodeURIComponent(id)}/name`;
  const key = ["job-name", source, id];
  const query = useQuery({
    queryKey: key,
    queryFn: ({ signal }) => get<{ name: string | null }>(path, signal),
    retry: false,
  });
  const save = useMutation({
    mutationFn: () => post<{ name: string | null }>(path, { name: draft }),
    onSuccess: (value) => {
      cache.setQueryData(key, value);
      void cache.invalidateQueries({ queryKey: ["jobs"] });
      void cache.invalidateQueries({ queryKey: ["calibre-jobs"] });
      void cache.invalidateQueries({ queryKey: ["work-neighbors"] });
      setOpened(false);
    },
  });
  return (
    <>
      <Group align="center" wrap="nowrap">
        <div style={{ minWidth: 0, overflowWrap: "anywhere" }}>
          <PageTitle
            title={query.data?.name || title}
            description={description}
          />
        </div>
        {editable && query.isSuccess && (
          <ActionIcon
            style={{ flexShrink: 0 }}
            variant="subtle"
            size="lg"
            aria-label={t("jobNames.edit")}
            onClick={() => {
              setDraft(query.data.name ?? "");
              save.reset();
              setOpened(true);
            }}
          >
            <Pencil size={20} />
          </ActionIcon>
        )}
      </Group>
      <Modal
        opened={opened}
        onClose={() => {
          if (!save.isPending) setOpened(false);
        }}
        title={t("jobNames.edit")}
      >
        <form
          onSubmit={(event) => {
            event.preventDefault();
            save.mutate();
          }}
        >
          <TextInput
            aria-label={t("jobNames.name")}
            description={t("jobNames.help")}
            value={draft}
            maxLength={150}
            data-autofocus
            disabled={save.isPending}
            onChange={(event) => setDraft(event.currentTarget.value)}
          />
          {save.isError && (
            <Failure error={save.error} retry={() => save.mutate()} />
          )}
          <Group justify="flex-end" mt="md">
            <Button
              variant="default"
              disabled={save.isPending}
              onClick={() => setOpened(false)}
            >
              {t("common.cancel")}
            </Button>
            <Button
              type="submit"
              loading={save.isPending}
              disabled={draft.trim() === (query.data?.name ?? "")}
            >
              {t("common.save")}
            </Button>
          </Group>
        </form>
      </Modal>
    </>
  );
}
