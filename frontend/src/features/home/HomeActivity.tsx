import { Badge } from "@mantine/core";
import { useQuery } from "@tanstack/react-query";
import { AlertTriangle, CalendarClock } from "lucide-react";
import { Link } from "react-router-dom";
import { useTranslation } from "react-i18next";
import { get } from "../../api/catalog";
import { Failure, Loading } from "../../components/Feedback";
import { useLocale } from "../../locales/useLocale";
import { homeReads } from "./homeReads";

type Attention = {
  id: string;
  source: "downloads" | "calibre";
  kind: "normal" | "cleanup";
  state: string;
  message: string | null;
  updatedAt: string | null;
  href: string;
};
type Task = {
  id: string;
  name: string;
  operation: string;
  enabled: boolean;
  running: boolean;
  nextRun: string | null;
  lastRun: string | null;
  lastResult: string | null;
};
export function HomeAttention() {
  const { t } = useTranslation();
  const { date, status } = useLocale();
  const result = useQuery({
    queryKey: ["home-attention"],
    queryFn: ({ signal }) =>
      homeReads.run(
        () =>
          get<{ items: Attention[]; total: number }>("/home/attention", signal),
        signal,
      ),
  });
  return (
    <section className="home-panel" aria-labelledby="home-attention-title">
      <div className="home-section-title">
        <h2 id="home-attention-title">
          <AlertTriangle size={20} />
          {t("home.attention")}
        </h2>
        {result.data && (
          <Badge color={result.data.total ? "orange" : "gray"}>
            {result.data.total}
          </Badge>
        )}
      </div>
      {result.isPending ? (
        <Loading />
      ) : result.isError ? (
        <Failure error={result.error} retry={() => result.refetch()} />
      ) : !result.data.items.length ? (
        <p className="muted">{t("home.noAttention")}</p>
      ) : (
        <>
          <ul className="home-activity-list">
            {result.data.items.map((item) => (
              <li key={`${item.source}:${item.kind}:${item.id}`}>
                <div className="home-activity-heading">
                  <Link to={item.href}>
                    {t(
                      item.kind === "cleanup"
                        ? "home.cleanupWork"
                        : item.source === "downloads"
                          ? "home.downloadWork"
                          : "home.calibreWork",
                    )}{" "}
                    <code title={item.id}>{item.id.slice(0, 8)}</code>
                  </Link>
                  <Badge color="orange">{status(item.state)}</Badge>
                </div>
                <p>{item.message || t("home.attentionReason")}</p>
                <time className="muted" dateTime={item.updatedAt ?? undefined}>
                  {date(item.updatedAt)}
                </time>
              </li>
            ))}
          </ul>
          {result.data.total > result.data.items.length && (
            <p className="muted home-small">
              {t("home.shownItems", {
                count: result.data.items.length,
                total: result.data.total,
              })}
            </p>
          )}
        </>
      )}
    </section>
  );
}
export function HomeScheduledTasks() {
  const { t } = useTranslation();
  const { date } = useLocale();
  const result = useQuery({
    queryKey: ["home-tasks"],
    queryFn: ({ signal }) =>
      homeReads.run(
        () => get<{ items: Task[]; total: number }>("/home/tasks", signal),
        signal,
      ),
  });
  return (
    <section className="home-panel" aria-labelledby="home-tasks-title">
      <div className="home-section-title">
        <h2 id="home-tasks-title">
          <CalendarClock size={20} />
          {t("home.scheduledTasks")}
        </h2>
        <Link to="/settings/tasks">{t("home.seeAll")}</Link>
      </div>
      {result.isPending ? (
        <Loading />
      ) : result.isError ? (
        <Failure error={result.error} retry={() => result.refetch()} />
      ) : !result.data.items.length ? (
        <p className="muted">{t("home.noScheduledTasks")}</p>
      ) : (
        <>
          <ul className="home-activity-list home-task-list">
            {result.data.items.map((task) => (
              <li key={task.id}>
                <div className="home-task-name">
                  <Link to="/settings/tasks">{task.name}</Link>
                  <span className="muted">
                    {t(`scheduledTasks.operations.${task.operation}`)}
                  </span>
                </div>
                <Badge
                  color={
                    task.running ? "teal" : task.enabled ? "green" : "yellow"
                  }
                >
                  {t(
                    task.running
                      ? "scheduledTasks.results.RUNNING"
                      : task.enabled
                        ? "home.taskEnabled"
                        : "home.taskDisabled",
                  )}
                </Badge>
                <dl className="home-task-facts">
                  <div>
                    <dt>{t("home.nextTaskRun")}</dt>
                    <dd>{date(task.enabled ? task.nextRun : null)}</dd>
                  </div>
                  <div>
                    <dt>{t("home.lastTaskRun")}</dt>
                    <dd>{date(task.lastRun)}</dd>
                  </div>
                  <div>
                    <dt>{t("home.lastTaskResult")}</dt>
                    <dd>
                      {task.lastResult
                        ? t(`scheduledTasks.results.${task.lastResult}`)
                        : "—"}
                    </dd>
                  </div>
                </dl>
              </li>
            ))}
          </ul>
          {result.data.total > result.data.items.length && (
            <p className="muted home-small">
              {t("home.shownItems", {
                count: result.data.items.length,
                total: result.data.total,
              })}
            </p>
          )}
        </>
      )}
    </section>
  );
}
