import { createContext, useContext, useState, type ReactNode } from "react";
import { Alert, Button, Checkbox, Select, Stack } from "@mantine/core";
import { Upload } from "lucide-react";
import { useMutation, useQueryClient } from "@tanstack/react-query";
import { useNavigate } from "react-router-dom";
import { useTranslation } from "react-i18next";
import { AppModal, ModalActions } from "../../components/AppModal";
import { TableAction } from "../../components/TableAction";
import { Loading, Failure } from "../../components/Feedback";
import { useAuth } from "../auth/Auth";
import { post } from "../downloads/shared";
import { useLocale } from "../../locales/useLocale";

type Candidate = {
  recordId: string;
  index: number;
  name: string;
  size: number;
  expectedSize?: number;
  eplId: number;
  revision: number;
};
type Prepared = {
  libraries: { id: string; name: string }[];
  library: string;
  files: Candidate[];
  books?: { eplId: number; revision: number; title: string }[];
  missing?: { eplId: number; revision: number; title: string }[];
};
type Selection = { ids: string[] } | { filters: Record<string, unknown> };
const Sender = createContext<((selection: Selection) => void) | null>(null);
const requestIdentifier = () => `${Date.now()}-${Math.random().toString(36).slice(2)}`;
const key = (file: Candidate) => `${file.recordId}:${file.index}`;
export function CalibreSendProvider({ children }: { children: ReactNode }) {
  const [opened, setOpened] = useState(false),
    [selection, setSelection] = useState<Selection>({ ids: [] }),
    [download, setDownload] = useState(false),
    [requestId, setRequestId] = useState(""),
    [chosen, setChosen] = useState<string[]>([]),
    [library, setLibrary] = useState<string | null>(null);
  const { t } = useTranslation();
  const auth = useAuth();
  const { revision } = useLocale();
  const navigate = useNavigate();
  const cache = useQueryClient();
  const prepare = useMutation({
    mutationFn: (selected: Selection) =>
      post<Prepared>("filters" in selected ? "/calibre/transfers/catalog/prepare" : "/calibre/transfers/prepare", selected),
    retry: false,
    onSuccess: (data) => {
      setChosen(data.files.map(key));
      setLibrary(
        data.libraries.some((l) => l.id === data.library)
          ? data.library
          : (data.libraries[0]?.id ?? null),
      );
    },
  });
  const send = useMutation({
    mutationFn: () =>
      post<{ id: string }>("filters" in selection ? "/calibre/transfers/catalog" : "/calibre/transfers", {
        ...("filters" in selection ? {id: requestId, books: prepare.data?.books, download, pending: prepare.data?.missing?.map(book => book.eplId) ?? []} : {}),
        library,
        files: prepare.data?.files
          .filter((f) => chosen.includes(key(f)))
          .map((f) => ({ recordId: f.recordId, index: f.index })),
      }),
    retry: false,
    onSuccess: (job) => {
      setOpened(false);
      void cache.invalidateQueries({ queryKey: ["calibre-jobs"] });
      navigate(`/calibre/jobs/${job.id}`);
    },
  });
  return (
    <Sender.Provider
      value={(selected) => {
        setSelection(selected);
        setDownload(false);
        setRequestId(requestIdentifier());
        prepare.reset();
        send.reset();
        setOpened(true);
        prepare.mutate(selected);
      }}
    >
      {children}
      <AppModal
        width="wide"
        opened={opened}
        onClose={() => {
          if (!send.isPending) setOpened(false);
        }}
        title={t("calibreSend.title")}
        icon={Upload}
      >
        {prepare.isPending ? (
          <Loading />
        ) : prepare.isError ? (
          <Failure error={prepare.error} retry={() => prepare.mutate(selection)} />
        ) : (
          prepare.data && (
            <Stack gap="md">
              <p>{t("calibreSend.help")}</p>
              <Select
                label={t("calibre.library")}
                data={prepare.data.libraries.map((l) => ({
                  value: l.id,
                  label: l.name,
                }))}
                value={library}
                onChange={(value) => {setLibrary(value);setRequestId(requestIdentifier());}}
                allowDeselect={false}
                disabled={send.isPending}
              />
              {prepare.data.files
                .filter(
                  (file) =>
                    chosen.includes(key(file)) &&
                    file.expectedSize != null &&
                    file.expectedSize !== file.size,
                )
                .map((file) => (
                  <Alert color="yellow" key={key(file)}>
                    {file.name}: {t("epubDownload.sizeWarning")}
                  </Alert>
                ))}
              {!!prepare.data.missing?.length && <>
                <Alert color="yellow">{t("calibreSend.missing", {count: prepare.data.missing.length})}</Alert>
                <Checkbox
                  label={t("calibreSend.downloadMissing")}
                  checked={download}
                  disabled={send.isPending || !auth.can("TORRENT_SEND") || !auth.can("TORRENT_SYNC")}
                  onChange={event => {setDownload(event.currentTarget.checked);setRequestId(requestIdentifier());}}
                />
              </>}
              <fieldset className="send-options-group">
                <legend>{t("calibreSend.files")}</legend>
                <Stack gap="sm">
                  {prepare.data.files.map((file) => (
                    <Checkbox
                      key={key(file)}
                      label={`${file.name} · EPL ID ${file.eplId} · ${t("downloads.revision")} ${revision(file.revision)}`}
                      checked={chosen.includes(key(file))}
                      disabled={send.isPending}
                      onChange={(e) => {
                        const checked = e.currentTarget.checked;
                        setRequestId(requestIdentifier());
                        setChosen((previous) =>
                          checked
                            ? [...previous, key(file)]
                            : previous.filter((value) => value !== key(file)),
                        );
                      }}
                    />
                  ))}
                </Stack>
              </fieldset>
              <p className="muted">{t("calibreSend.duplicates")}</p>
              {send.isError && (
                <Alert color="red">
                  {send.error.message || t("calibreSend.failed")}
                </Alert>
              )}
            </Stack>
          )
        )}
        <ModalActions>
          <Button
            variant="default"
            onClick={() => setOpened(false)}
            disabled={send.isPending}
          >
            {t("import.cancel")}
          </Button>
          <Button
            disabled={!prepare.isSuccess || !library || (!chosen.length && !(download && prepare.data?.missing?.length))}
            loading={send.isPending}
            onClick={() => send.mutate()}
          >
            {t("calibreSend.title")}
          </Button>
        </ModalActions>
      </AppModal>
    </Sender.Provider>
  );
}
export function SendToCalibre({
  ids,
  filters,
  count,
  inline = false,
}: {
  ids?: string[];
  filters?: Record<string, unknown>;
  count?: number;
  inline?: boolean;
}) {
  const open = useContext(Sender);
  const { can } = useAuth();
  const { t } = useTranslation();
  if (
    !open ||
    !(filters ? count : ids?.length) ||
    (filters && !can("CATALOG_READ")) ||
    !can("CALIBRE_SEND") ||
    !can("DOWNLOADS_FILES_READ")
  )
    return null;
  return inline ? (
    <TableAction label={t("calibreSend.title")} onClick={() => open(filters ? { filters } : { ids: ids ?? [] })}>
      <Upload size={16} aria-hidden="true" />
    </TableAction>
  ) : (
    <Button
      variant="light"
      disabled={filters != null && (count ?? 0) > 1000}
      title={filters != null && (count ?? 0) > 1000 ? t("calibreSend.catalogLimit") : undefined}
      leftSection={<Upload size={16} />}
      onClick={() => open(filters ? { filters } : { ids: ids ?? [] })}
    >
      {t("calibreSend.title")}
    </Button>
  );
}
