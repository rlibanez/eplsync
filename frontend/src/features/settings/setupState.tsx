import { useEffect } from "react";
import { useQuery, useMutation, useQueryClient } from "@tanstack/react-query";
import { useNavigate } from "react-router-dom";
import { Button } from "@mantine/core";
import { useTranslation } from "react-i18next";
import { get } from "../../api/catalog";
import { post } from "../downloads/shared";
import { useAuth } from "../auth/Auth";
import { Failure } from "../../components/Feedback";
export interface SetupProgress {
  step: number;
  status: "active" | "deferred" | "completed";
  torrent: "pending" | "done" | "skipped";
  calibre: "pending" | "done" | "skipped";
}
export function useSetupProgress() {
  const admin = useAuth().user?.role === "ADMIN";
  return useQuery({
    queryKey: ["installation-setup"],
    queryFn: ({ signal }) => get<SetupProgress>("/settings/setup", signal),
    enabled: admin,
    retry: false,
    refetchOnWindowFocus: false,
  });
}
export function SetupEntry({ automatic = false }: { automatic?: boolean }) {
  const { t } = useTranslation();
  const admin = useAuth().user?.role === "ADMIN";
  const state = useSetupProgress();
  const cache = useQueryClient();
  const navigate = useNavigate();
  const open = useMutation({
    mutationFn: (value: SetupProgress) =>
      post<SetupProgress>("/settings/setup", value),
    onSuccess: (value) => {
      cache.setQueryData(["installation-setup"], value);
      navigate("/settings/setup");
    },
  });
  useEffect(() => {
    if (automatic && state.data?.status === "active")
      navigate("/settings/setup", { replace: true });
  }, [automatic, state.data, navigate]);
  if (!admin || automatic) return null;
  return (
    <section className="panel settings-section">
      <h2>{t("setup.title")}</h2>
      <p>{t("setup.description")}</p>
      <Button
        loading={open.isPending}
        disabled={!state.data}
        onClick={() =>
          open.mutate(
            state.data!.status === "completed"
              ? {
                  step: 0,
                  status: "active",
                  torrent: "pending",
                  calibre: "pending",
                }
              : { ...state.data!, status: "active" },
          )
        }
      >
        {t("setup.open")}
      </Button>
      {(state.isError || open.isError) && (
        <Failure
          error={state.error ?? open.error!}
          retry={() => state.refetch()}
        />
      )}
    </section>
  );
}
