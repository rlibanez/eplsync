import { useState } from "react";
import { Alert, Button } from "@mantine/core";
import { useTranslation } from "react-i18next";
import { SyncReport, type SyncResult } from "../downloads/SyncReport";
import { post } from "../downloads/shared";
export function SyncPage() {
  const { t } = useTranslation();
  const [report, setReport] = useState<SyncResult>();
  const [pending, setPending] = useState(false);
  const [error, setError] = useState("");
  async function run(dryRun: boolean) {
    setPending(true);
    setError("");
    try {
      setReport(
        await post<SyncResult>("/torrent/downloads/sync", {
          dryRun,
          includeDetails: true,
        }),
      );
    } catch (e) {
      setError(String(e));
    } finally {
      setPending(false);
    }
  }
  return (
    <>
      <h1>{t("nav.downloadState")}</h1>
      {error && <Alert color="red">{error}</Alert>}
      <Button disabled={pending} onClick={() => void run(true)}>
        {t("syncReport.previewAction")}
      </Button>{" "}
      <Button disabled={pending} onClick={() => void run(false)}>
        {t("downloads.sync")}
      </Button>
      {report && (
        <SyncReport report={report} onClose={() => setReport(undefined)} />
      )}
    </>
  );
}
