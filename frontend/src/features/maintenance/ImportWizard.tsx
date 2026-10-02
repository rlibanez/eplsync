import { useEffect, useState } from "react";
import {
  ActionIcon,
  Button,
  FileInput,
  Loader,
  Radio,
  Stack,
  TextInput,
  Tooltip,
} from "@mantine/core";
import { Download, Copy, Upload } from "lucide-react";
import { useTranslation } from "react-i18next";
import { AppModal, ModalActions } from "../../components/AppModal";
import { get } from "../../api/catalog";
import { copyDetails } from "../events/copyDetails";
import type { ImportSource } from "./ImportProvider";
interface Archive {
  id: string;
  name: string;
  sourceUrl: string | null;
  storedAt: string;
  expiresAt: string;
  size: number;
  sha256: string;
  csvName: string;
  csvModifiedAt: string | null;
}
export function ImportWizard({
  opened,
  onClose,
  onStart,
}: {
  opened: boolean;
  onClose: () => void;
  onStart: (mode: "preview" | "update", source: ImportSource) => void;
}) {
  const { t, i18n } = useTranslation();
  const [step, setStep] = useState(0);
  const [source, setSource] = useState("URL");
  const [url, setUrl] = useState("");
  const [file, setFile] = useState<File | null>(null);
  const [archive, setArchive] = useState<Archive | null>(null);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState(false);
  const [copy, setCopy] = useState("copyHash");
  const [attempt, setAttempt] = useState(0);
  useEffect(() => {
    if (!opened) return;
    const abort = new AbortController();
    setStep(0);
    setFile(null);
    setArchive(null);
    setLoading(true);
    setError(false);
    setCopy("copyHash");
    void get<{ defaultUrl: string; archive: Archive | null }>(
      "/catalog/import/source",
      abort.signal,
    )
      .then((data) => {
        if (!abort.signal.aborted) {
          setUrl(data.defaultUrl);
          setArchive(data.archive);
          setSource(data.archive ? "SAVED" : "URL");
        }
      })
      .catch(() => {
        if (!abort.signal.aborted) setError(true);
      })
      .finally(() => {
        if (!abort.signal.aborted) setLoading(false);
      });
    return () => abort.abort();
  }, [opened, attempt]);
  let validUrl = false;
  try {
    const parsed = new URL(url);
    validUrl =
      ["http:", "https:"].includes(parsed.protocol) && !!parsed.hostname;
  } catch {
    /* URL field is incomplete. */
  }
  const validFile =
    !!file &&
    /\.zip$/i.test(file.name) &&
    file.size > 0 &&
    file.size <= 128 * 1024 * 1024;
  const valid =
    source === "SAVED" ? !!archive : source === "URL" ? validUrl : validFile;
  const date = (value: string) => new Date(value).toLocaleString(i18n.language);
  const start = (mode: "preview" | "update") => {
    if (!valid) return;
    const input: ImportSource =
      source === "SAVED"
        ? { source: "SAVED", archiveId: archive!.id }
        : source === "URL"
          ? { source: "URL", url: url.trim() }
          : { source: "UPLOAD", file: file! };
    onStart(mode, input);
    onClose();
  };
  return (
    <AppModal
      opened={opened}
      onClose={onClose}
      title={t("import.wizardTitle")}
      icon={Download}
      size="lg"
      centered
    >
      <p className="muted">
        {t(step === 0 ? "import.chooseSource" : "import.chooseOperation")}
      </p>
      {loading ? (
        <Loader aria-label={t("import.loadingSource")} />
      ) : error ? (
        <>
          <p role="alert">{t("import.sourceError")}</p>
          <Button onClick={() => setAttempt((n) => n + 1)}>
            {t("import.retrySource")}
          </Button>
        </>
      ) : step === 0 ? (
        <Stack gap="md">
          <Radio.Group
            value={source}
            onChange={setSource}
            aria-label={t("import.chooseSource")}
          >
            <Stack gap="sm">
              {archive && (
                <Radio value="SAVED" label={t("import.savedArchive")} />
              )}
              <Radio value="URL" label="URL" />
              <Radio value="UPLOAD" label={t("import.uploadZip")} />
            </Stack>
          </Radio.Group>
          {source === "URL" && (
            <TextInput
              type="url"
              label="URL"
              required
              value={url}
              onChange={(e) => setUrl(e.currentTarget.value)}
            />
          )}
          {source === "UPLOAD" && (
            <FileInput
              label={t("import.zipFile")}
              placeholder={t("import.chooseZip")}
              accept=".zip"
              value={file}
              onChange={setFile}
              leftSection={<Upload size={17} />}
              clearable
              error={file && !validFile ? t("import.invalidZip") : undefined}
            />
          )}
          {source === "SAVED" && archive && (
            <dl className="import-archive-details">
              <div>
                <dt>{t("import.zipFile")}</dt>
                <dd>{archive.name}</dd>
              </div>
              <div>
                <dt>
                  {t(
                    archive.csvModifiedAt
                      ? "import.csvDate"
                      : "import.storedAt",
                  )}
                </dt>
                <dd>
                  {archive.csvModifiedAt?.replace("T", " ") ??
                    date(archive.storedAt)}
                </dd>
              </div>
              {archive.csvModifiedAt && (
                <div>
                  <dt>{t("import.storedAt")}</dt>
                  <dd>{date(archive.storedAt)}</dd>
                </div>
              )}
              <div>
                <dt>{t("import.zipSize")}</dt>
                <dd>
                  {(archive.size / 1024 / 1024).toLocaleString(i18n.language, {
                    maximumFractionDigits: 2,
                  })}{" "}
                  MiB
                </dd>
              </div>
              <div>
                <dt>SHA-256 (ZIP)</dt>
                <dd className="import-archive-hash">
                  <code>{archive.sha256}</code>
                  <Tooltip label={t(`import.${copy}`)}>
                    <ActionIcon
                      variant="subtle"
                      aria-label={t("import.copyHash")}
                      onClick={() => {
                        void copyDetails(archive.sha256)
                          .then(() => setCopy("hashCopied"))
                          .catch(() => setCopy("hashCopyFailed"));
                      }}
                    >
                      <Copy size={17} />
                    </ActionIcon>
                  </Tooltip>
                </dd>
              </div>
            </dl>
          )}
        </Stack>
      ) : (
        <>
          <p className="import-wizard-selection">
            {source === "SAVED"
              ? archive?.name
              : source === "URL"
                ? url
                : file?.name}
          </p>
          <p>{t("import.operationDescription")}</p>
        </>
      )}
      <ModalActions>
        <Button
          variant="default"
          onClick={step === 0 ? onClose : () => setStep(0)}
        >
          {t(step === 0 ? "import.cancel" : "import.back")}
        </Button>
        {step === 0 ? (
          <Button
            disabled={loading || error || !valid}
            onClick={() => setStep(1)}
          >
            {t("import.next")}
          </Button>
        ) : (
          <>
            <Button
              variant="default"
              disabled={!valid}
              onClick={() => start("preview")}
            >
              {t("import.preview")}
            </Button>
            <Button disabled={!valid} onClick={() => start("update")}>
              {t("import.update")}
            </Button>
          </>
        )}
      </ModalActions>
    </AppModal>
  );
}
