import { useEffect, useRef, useState } from "react";
import { useLocation } from "react-router-dom";
import { Button, Select, TextInput } from "@mantine/core";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { useTranslation } from "react-i18next";
import { Trash2 } from "lucide-react";
import { AppModal, ModalActions } from "../../components/AppModal";
import { Failure } from "../../components/Feedback";
import { get } from "../../api/catalog";
import { post } from "../downloads/shared";
import { localDay } from "../catalog/CatalogFilters";
import { dateBounds } from "./eventTypes";
import { useNotifications } from "../notifications/Notifications";
export function EventMaintenance() {
  const { t } = useTranslation();
  const { hash } = useLocation();
  const section = useRef<HTMLElement>(null);
  useEffect(() => {
    if (hash === "#events") section.current?.scrollIntoView({ block: "start" });
  }, [hash]);
  const cache = useQueryClient();
  const { notify } = useNotifications();
  const [scope, setScope] = useState("range");
  const [from, setFrom] = useState("");
  const [to, setTo] = useState("");
  const [confirm, setConfirm] = useState(false);
  const retention = useQuery({
    queryKey: ["event-retention"],
    queryFn: ({ signal }) =>
      get<{ maxCount: number; maxAgeDays: number }>(
        "/events/retention",
        signal,
      ),
  });
  const today = localDay();
  const valid =
    scope === "all" ||
    Boolean((from || to) && (!from || from <= today) && (!to || to <= today) && (!from || !to || from <= to));
  const remove = useMutation({
    meta: {
      silentSuccess: true,
      notice: {
        title: "events.maintenance",
        error: "notifications.operationError",
      },
    },
    mutationFn: () =>
      post<{ deleted: number }>("/events/delete", {
        confirm: true,
        ...(scope === "all"
          ? {}
          : dateBounds(from, to || today)),
      }),
    onSuccess: (result) => {
      setConfirm(false);
      notify({
        title: t("events.maintenance"),
        message: t("events.deleted", { count: result.deleted }),
        tone: "success",
      });
      void cache.invalidateQueries({ queryKey: ["events"] });
    },
  });
  return (
    <section ref={section} id="events" className="panel settings-section">
      <h2>{t("events.maintenance")}</h2>
      {retention.data && (
        <p>
          {t("events.retention", {
            count: retention.data.maxCount,
            days: retention.data.maxAgeDays,
          })}
        </p>
      )}
      {retention.isError && (
        <Failure
          error={retention.error}
          retry={() => void retention.refetch()}
        />
      )}
      <div className="filters event-deletion-filters">
        <Select
          label={t("events.deleteScope")}
          value={scope}
          allowDeselect={false}
          onChange={(value) => setScope(value!)}
          data={["range", "all"].map((value) => ({
            value,
            label: t("events.scopes." + value),
          }))}
        />
        {scope === "range" && (
          <TextInput
            type="date"
            label={t("filters.from")}
            value={from}
            max={to || today}
            onChange={(e) => setFrom(e.currentTarget.value)}
          />
        )}
        {scope !== "all" && (
          <TextInput
            type="date"
            label={t("filters.to")}
            value={to}
            min={from || undefined}
            max={today}
            onChange={(e) => setTo(e.currentTarget.value)}
          />
        )}
        <Button
          color="red"
          variant="light"
          disabled={!valid || remove.isPending}
          onClick={() => setConfirm(true)}
        >
          {t("events.delete")}
        </Button>
      </div>
      <AppModal
        icon={Trash2}
        opened={confirm}
        title={t("events.delete")}
        onClose={() => {
          if (!remove.isPending) setConfirm(false);
        }}
        withCloseButton={!remove.isPending}
        closeOnClickOutside={!remove.isPending}
        closeOnEscape={!remove.isPending}
      >
        <p>{t("events.deleteConfirm")}</p>
        <p>
          {t("events.scopes." + scope)}
          {scope !== "all" &&
            ": " + (from || t("events.fromBeginning")) + " → " + (to || today)}
        </p>
        <ModalActions>
          <Button
            variant="default"
            disabled={remove.isPending}
            onClick={() => setConfirm(false)}
          >
            {t("import.cancel")}
          </Button>
          <Button
            color="red"
            loading={remove.isPending}
            onClick={() => remove.mutate()}
          >
            {t("events.delete")}
          </Button>
        </ModalActions>
      </AppModal>
    </section>
  );
}
