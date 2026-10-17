import {
  createContext,
  useContext,
  useMemo,
  useState,
  type ReactNode,
} from "react";
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
  deferred?: { eplId: number; revision: number; title: string }[];
  deferredRecords?: string[];
  missing?: { eplId: number; revision: number; title: string }[];
};
type Selection = { ids: string[] } | { filters: Record<string, unknown> };
const Sender = createContext<((selection: Selection) => void) | null>(null);
const requestIdentifier = () =>
  `${Date.now()}-${Math.random().toString(36).slice(2)}`;
const key = (file: Candidate) => `${file.recordId}:${file.index}`;
export function CalibreSendProvider({ children }: { children: ReactNode }) {
  const [opened, setOpened] = useState(false),
    [selection, setSelection] = useState<Selection>({ ids: [] }),
    [download, setDownload] = useState(false),
    [requestId, setRequestId] = useState(""),
    [chosen, setChosen] = useState<string[]>([]),
    [library, setLibrary] = useState<string | null>(null);
  const [filePage, setFilePage] = useState(0);
  const chosenSet = useMemo(() => new Set(chosen), [chosen]);
  const { t } = useTranslation();
  const auth = useAuth();
  const { revision } = useLocale();
  const navigate = useNavigate();
  const cache = useQueryClient();
  const prepare = useMutation({
    mutationFn: (selected: Selection) =>
      post<Prepared>(
        "filters" in selected
          ? "/calibre/transfers/catalog/prepare"
          : "/calibre/transfers/prepare",
        selected,
      ),
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
      post<{ id: string }>(
        "filters" in selection
          ? "/calibre/transfers/catalog"
          : "/calibre/transfers",
        {
          ...("filters" in selection
            ? {
                id: requestId,
                books: prepare.data?.books,
                download,
                pending: [
                  ...(download
                    ? (prepare.data?.missing?.map((book) => book.eplId) ?? [])
                    : []),
                  ...(prepare.data?.deferred?.map((book) => book.eplId) ?? []),
                ],
              }
            : {}),
          id: requestId,
          library,
          ...("filters" in selection || !prepare.data?.deferredRecords?.length
            ? {}
            : { deferredRecords: prepare.data.deferredRecords }),
          files: prepare.data?.files
            .filter((f) => chosenSet.has(key(f)))
            .map((f) => ({ recordId: f.recordId, index: f.index })),
        },
      ),
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
        setFilePage(0);
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
          <Failure
            error={prepare.error}
            retry={() => prepare.mutate(selection)}
          />
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
                onChange={(value) => {
                  setLibrary(value);
                  setRequestId(requestIdentifier());
                }}
                allowDeselect={false}
                disabled={send.isPending}
              />
              {prepare.data.files
                .filter(
                  (file) =>
                    chosenSet.has(key(file)) &&
                    file.expectedSize != null &&
                    file.expectedSize !== file.size,
                )
                .map((file) => (
                  <Alert color="yellow" key={key(file)}>
                    {file.name}: {t("epubDownload.sizeWarning")}
                  </Alert>
                ))}
              {!!(
                (prepare.data.deferred?.length ?? 0) ||
                (prepare.data.deferredRecords?.length ?? 0)
              ) && (
                <Alert>
                  {t("calibreSend.deferred", {
                    count:
                      prepare.data.deferred?.length ||
                      prepare.data.deferredRecords?.length,
                  })}
                </Alert>
              )}
              {!!prepare.data.files.length && (
                <fieldset className="send-options-group">
                  <legend>{t("calibreSend.files")}</legend>
                  <Stack gap="sm">
                    {prepare.data.files
                      .slice(filePage * 100, (filePage + 1) * 100)
                      .map((file) => (
                        <Checkbox
                          key={key(file)}
                          label={`${file.name} · EPL ID ${file.eplId} · ${t("downloads.revision")} ${revision(file.revision)}`}
                          checked={chosenSet.has(key(file))}
                          disabled={send.isPending}
                          onChange={(e) => {
                            const checked = e.currentTarget.checked;
                            setRequestId(requestIdentifier());
                            setChosen((previous) =>
                              checked
                                ? [...previous, key(file)]
                                : previous.filter(
                                    (value) => value !== key(file),
                                  ),
                            );
                          }}
                        />
                      ))}
                  </Stack>
                  {prepare.data.files.length > 100 && (
                    <div className="pagination">
                      <Button
                        variant="subtle"
                        disabled={!filePage}
                        onClick={() => setFilePage((page) => page - 1)}
                      >
                        {t("catalog.previous")}
                      </Button>
                      <span>
                        {filePage + 1} /{" "}
                        {Math.ceil(prepare.data.files.length / 100)}
                      </span>
                      <Button
                        variant="subtle"
                        disabled={
                          (filePage + 1) * 100 >= prepare.data.files.length
                        }
                        onClick={() => setFilePage((page) => page + 1)}
                      >
                        {t("catalog.next")}
                      </Button>
                    </div>
                  )}
                </fieldset>
              )}
              {!!prepare.data.missing?.length && (
                <>
                  <Alert color="yellow">
                    {t("calibreSend.missing", {
                      count: prepare.data.missing.length,
                    })}
                  </Alert>
                  <Checkbox
                    label={t("calibreSend.downloadMissing")}
                    checked={download}
                    disabled={
                      send.isPending ||
                      !auth.can("TORRENT_SEND") ||
                      !auth.can("TORRENT_SYNC")
                    }
                    onChange={(event) => {
                      setDownload(event.currentTarget.checked);
                      setRequestId(requestIdentifier());
                    }}
                  />
                </>
              )}

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
            disabled={
              !prepare.isSuccess ||
              !library ||
              (!chosen.length &&
                !prepare.data?.deferred?.length &&
                !prepare.data?.deferredRecords?.length &&
                !(download && prepare.data?.missing?.length))
            }
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
    <TableAction
      label={t("calibreSend.title")}
      onClick={() => open(filters ? { filters } : { ids: ids ?? [] })}
    >
      <Upload size={16} aria-hidden="true" />
    </TableAction>
  ) : (
    <Button
      variant="light"
      leftSection={<Upload size={16} />}
      onClick={() => open(filters ? { filters } : { ids: ids ?? [] })}
    >
      {t("calibreSend.title")}
    </Button>
  );
}
