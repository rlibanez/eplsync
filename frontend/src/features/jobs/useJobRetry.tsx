import { useEffect, useState } from "react";
import { Alert, Button, Checkbox } from "@mantine/core";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { Link, useNavigate } from "react-router-dom";
import { RotateCcw } from "lucide-react";
import { useTranslation } from "react-i18next";
import { useAuth } from "../auth/Auth";
import { get } from "../../api/catalog";
import { post } from "../downloads/shared";
import { AppModal, ModalActions } from "../../components/AppModal";
import { Failure, Loading } from "../../components/Feedback";
interface LinkInfo {
  kind: string;
  jobId: string;
  parentId: string;
  rootId: string;
}
interface History {
  source: LinkInfo | null;
  retries: LinkInfo[];
  terminal: boolean;
  failed: number;
  reason: string | null;
}
interface Preview {
  token: string;
  send: number;
  verify: number;
  waiting: number;
  cleanup: number;
  excluded: number;
  issues: { id: string; detail: string }[];
  changes: string[];
  library: string;
}
export function useJobRetry(
  kind: "downloads" | "calibre",
  id: string | undefined,
  state: string | undefined,
  selected: string[],
  clear: () => void,
) {
  const { t } = useTranslation();
  const { can } = useAuth();
  const cache = useQueryClient(),
    navigate = useNavigate();
  const manage = can(
    kind === "downloads" ? "TORRENT_JOBS_MANAGE" : "CALIBRE_JOBS_MANAGE",
  );
  const [opened, setOpened] = useState(false),
    [confirmed, setConfirmed] = useState(false);
  const url = `/job-retries/${kind}/${id}`;
  const history = useQuery({
    queryKey: ["job-retries", kind, id, state],
    queryFn: ({ signal }) => get<History>(url, signal),
    enabled: !!id && manage,
    retry: false,
  });
  const prepare = useMutation({
    mutationFn: (items: string[] | null) =>
      post<Preview>(`${url}/preview`, { items, failedOnly: items === null }),
    retry: false,
  });
  const create = useMutation({
    mutationFn: () =>
      post<LinkInfo>(url, {
        token: prepare.data!.token,
        confirmChanges: confirmed,
      }),
    retry: false,
    onSuccess: (result) => {
      clear();
      setOpened(false);
      for (const key of ["jobs", "calibre-jobs", "job-retries"])
        void cache.invalidateQueries({ queryKey: [key] });
      navigate(`/${kind}/jobs/${result.jobId}`);
    },
  });
  useEffect(() => {
    setOpened(false);
    prepare.reset();
    create.reset();
  }, [kind, id]);
  function start(items: string[] | null) {
    setConfirmed(false);
    create.reset();
    setOpened(true);
    prepare.mutate(items);
  }
  const permitted =
    kind === "downloads"
      ? can("TORRENT_SEND") || can("TORRENT_CLEANUP")
      : can("CALIBRE_SEND") && can("DOWNLOADS_FILES_READ");
  const selectable =
    manage &&
    permitted &&
    !!history.data?.terminal &&
    ["COMPLETED", "CANCELLED"].includes(state ?? "");
  const count = prepare.data
    ? prepare.data.send +
      prepare.data.verify +
      prepare.data.waiting +
      prepare.data.cleanup
    : 0;
  const href = (link: LinkInfo, id: string) => `/${link.kind}/jobs/${id}`;
  return {
    selectable,
    action: (
      <Button onClick={() => start(selected)}>
        {t("jobRetry.selected", { count: selected.length })}
      </Button>
    ),
    content: (
      <>
        {manage && history.isError && (
          <Failure error={history.error} retry={() => history.refetch()} />
        )}
        {history.data?.source && (
          <p className="muted">
            {t("jobRetry.parent")}{" "}
            <Link to={href(history.data.source, history.data.source.parentId)}>
              {history.data.source.parentId}
            </Link>
            {history.data.source.rootId !== history.data.source.parentId && (
              <>
                {" "}
                · {t("jobRetry.root")}{" "}
                <Link
                  to={href(history.data.source, history.data.source.rootId)}
                >
                  {history.data.source.rootId}
                </Link>
              </>
            )}
          </p>
        )}
        {!!history.data?.retries.length && (
          <p>
            {t("jobRetry.children")}{" "}
            {history.data.retries.map((link, index) => (
              <span key={link.jobId}>
                {index > 0 && " · "}
                <Link to={href(link, link.jobId)}>{link.jobId}</Link>
              </span>
            ))}
          </p>
        )}
        {selectable && !!history.data?.failed && (
          <div className="action-row">
            <Button
              variant="default"
              leftSection={<RotateCcw size={16} />}
              onClick={() => start(null)}
            >
              {t("jobRetry.failed", { count: history.data.failed })}
            </Button>
          </div>
        )}
        <AppModal
          width="normal"
          opened={opened}
          onClose={() => {
            if (!create.isPending) setOpened(false);
          }}
          title={t("jobRetry.title")}
          icon={RotateCcw}
        >
          <p>{t("jobRetry.help")}</p>
          {prepare.isPending && <Loading />}
          {prepare.isError && (
            <Failure
              error={prepare.error}
              retry={() => prepare.mutate(prepare.variables ?? null)}
            />
          )}
          {prepare.data && (
            <>
              {prepare.data.library && (
                <p>
                  {t("calibre.library")}: {prepare.data.library}
                </p>
              )}
              <dl className="import-summary">
                {(["send", "verify", "waiting", "cleanup"] as const).map(
                  (key) => (
                    <div key={key}>
                      <dt>{t(`jobRetry.${key}`)}</dt>
                      <dd>{prepare.data[key]}</dd>
                    </div>
                  ),
                )}
              </dl>
              {!!prepare.data.excluded && (
                <Alert
                  color="yellow"
                  title={t("jobRetry.excluded", {
                    count: prepare.data.excluded,
                  })}
                >
                  <ul>
                    {prepare.data.issues.map((issue) => (
                      <li key={issue.id}>{issue.detail}</li>
                    ))}
                  </ul>
                </Alert>
              )}
              {!!prepare.data.changes.length && (
                <Alert color="yellow">
                  <ul>
                    {prepare.data.changes.map((change) => (
                      <li key={change}>{change}</li>
                    ))}
                  </ul>
                  <Checkbox
                    label={t("jobRetry.confirmChanges")}
                    checked={confirmed}
                    onChange={(event) =>
                      setConfirmed(event.currentTarget.checked)
                    }
                  />
                </Alert>
              )}
              {!count && <p>{t("jobRetry.empty")}</p>}
            </>
          )}
          {create.isError && (
            <Failure error={create.error} retry={() => create.mutate()} />
          )}
          <ModalActions>
            <Button
              variant="default"
              disabled={create.isPending}
              onClick={() => setOpened(false)}
            >
              {t("import.cancel")}
            </Button>
            <Button
              loading={create.isPending}
              disabled={
                !count ||
                prepare.isPending ||
                (!!prepare.data?.changes.length && !confirmed)
              }
              onClick={() => create.mutate()}
            >
              {t("jobRetry.create")}
            </Button>
          </ModalActions>
        </AppModal>
      </>
    ),
  };
}
