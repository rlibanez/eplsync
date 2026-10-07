import { Link, useLocation } from "react-router-dom";
import { useTranslation } from "react-i18next";
import { useCoverTask, useCoverTaskCompletion } from "./coverApi";

export function CoverActivity() {
  const { t } = useTranslation();
  const location = useLocation();
  const { data } = useCoverTask();
  const task = data?.task;
  useCoverTaskCompletion(task);
  if (task?.state !== "RUNNING" || location.pathname === "/settings/catalog")
    return null;
  return (
    <div className="panel cover-activity" role="status">
      <Link to="/settings/catalog">
        {t("covers.running")} · {task.checked} / {task.total || "…"}
      </Link>
    </div>
  );
}
