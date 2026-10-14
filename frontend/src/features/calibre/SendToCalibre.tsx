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
import { useNotifications } from "../notifications/Notifications";
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
};
const Sender = createContext<((ids: string[]) => void) | null>(null);
const key = (file: Candidate) => `${file.recordId}:${file.index}`;
export function CalibreSendProvider({ children }: { children: ReactNode }) {
  const [opened, setOpened] = useState(false),
    [ids, setIds] = useState<string[]>([]),
    [chosen, setChosen] = useState<string[]>([]),
    [library, setLibrary] = useState<string | null>(null);
  const { t } = useTranslation();
  const { revision } = useLocale();
  const navigate = useNavigate();
  const cache = useQueryClient();
  const { notify } = useNotifications();
  const prepare = useMutation({
    mutationFn: (records: string[]) =>
      post<Prepared>("/calibre/transfers/prepare", { ids: records }),
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
      post<{ id: string }>("/calibre/transfers", {
        library,
        files: prepare.data?.files
          .filter((f) => chosen.includes(key(f)))
          .map((f) => ({ recordId: f.recordId, index: f.index })),
      }),
    retry: false,
    onSuccess: (job) => {
      setOpened(false);
      void cache.invalidateQueries({ queryKey: ["calibre-jobs"] });
      notify({ tone: "success", title: t("calibreSend.created"), message: "" });
      navigate(`/calibre/jobs/${job.id}`);
    },
  });
  return (
    <Sender.Provider
      value={(records) => {
        setIds([...records]);
        prepare.reset();
        send.reset();
        setOpened(true);
        prepare.mutate(records);
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
          <Failure error={prepare.error} retry={() => prepare.mutate(ids)} />
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
                onChange={setLibrary}
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
            disabled={!prepare.isSuccess || !library || !chosen.length}
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
  inline = false,
}: {
  ids: string[];
  inline?: boolean;
}) {
  const open = useContext(Sender);
  const { can } = useAuth();
  const { t } = useTranslation();
  if (
    !open ||
    !ids.length ||
    !can("CALIBRE_SEND") ||
    !can("DOWNLOADS_FILES_READ")
  )
    return null;
  return inline ? (
    <TableAction label={t("calibreSend.title")} onClick={() => open(ids)}>
      <Upload size={16} aria-hidden="true" />
    </TableAction>
  ) : (
    <Button
      variant="light"
      leftSection={<Upload size={16} />}
      onClick={() => open(ids)}
    >
      {t("calibreSend.title")}
    </Button>
  );
}
