import { useTranslation } from "react-i18next";
import { ExternalLink } from "lucide-react";
import { useQuery, useMutation, useQueryClient } from "@tanstack/react-query";
import { Alert, Button, Checkbox } from "@mantine/core";
import { get } from "../../api/catalog";
import { useAuth } from "../auth/Auth";
import { authRequest } from "../auth/transport";
import { useLocale } from "../../locales/useLocale";
import { Failure, Loading } from "../../components/Feedback";
import { OptionLabel } from "../downloads/SendOptions";
import {
  useApplicationUpdates,
  type ApplicationUpdateStatus,
} from "./applicationUpdates";

export function About() {
  const { t } = useTranslation();
  const auth = useAuth();
  const { date } = useLocale();
  const cache = useQueryClient();
  const version = useQuery({
    queryKey: ["application-version"],
    queryFn: ({ signal }) =>
      get<{ version: string; commit: string | null; releasesUrl: string }>(
        "/application/version",
        signal,
      ),
    staleTime: Infinity,
  });
  const updates = useApplicationUpdates();
  const save = useMutation({
    mutationFn: (automatic: boolean) =>
      authRequest<ApplicationUpdateStatus>(
        "/application/updates/settings",
        "PUT",
        { automatic },
      ),
    onSuccess: (data) =>
      cache.setQueryData(["application-updates", auth.user?.id], data),
  });
  const check = useMutation({
    mutationFn: () =>
      authRequest<ApplicationUpdateStatus>(
        "/application/updates/check",
        "POST",
      ),
    onSuccess: (data) =>
      cache.setQueryData(["application-updates", auth.user?.id], data),
  });
  const error = save.error || check.error || updates.error;
  return (
    <>
      <section className="panel settings-section about-section">
        <h2>{t("applicationVersion.installed")}</h2>
        {version.isPending ? (
          <Loading />
        ) : version.isError ? (
          <Failure error={version.error} retry={() => version.refetch()} />
        ) : (
          <p>
            {t("applicationVersion.version", { version: version.data.version })}
            {version.data.commit ? ` (${version.data.commit})` : ""}
          </p>
        )}
        <a
          className="back-link"
          href={version.data?.releasesUrl ?? "https://github.com/rlibanez/eplsync/releases"}
          target="_blank"
          rel="noopener noreferrer"
        >
          {t("applicationVersion.releaseNotes")}{" "}
          <ExternalLink size={16} aria-hidden="true" />
        </a>
      </section>
      {auth.user?.role === "ADMIN" && (
        <section className="panel settings-section about-section">
          <h2>{t("applicationVersion.updates")}</h2>
          {updates.data && (
            <>
              <Checkbox
                checked={updates.data.automatic}
                disabled={save.isPending}
                label={
                  <OptionLabel
                    text={t("applicationVersion.automatic")}
                    help={t("applicationVersion.automaticHelp")}
                  />
                }
                onChange={(event) => save.mutate(event.currentTarget.checked)}
              />
              <p>
                {t(`applicationVersion.states.${updates.data.state}`, {
                  version: updates.data.latestVersion,
                })}
              </p>
              {updates.data.checkedAt && (
                <p className="muted">
                  {t("applicationVersion.checkedAt", {
                    date: date(updates.data.checkedAt),
                  })}
                </p>
              )}
              {updates.data.state === "AVAILABLE" &&
                updates.data.releaseUrl && (
                  <Alert color="blue">
                    {t("applicationVersion.available", {
                      version: updates.data.latestVersion,
                    })}{" "}
                    <a
                      className="back-link"
                      href={updates.data.releaseUrl}
                      target="_blank"
                      rel="noopener noreferrer"
                    >
                      {t("applicationVersion.releaseNotes")}{" "}
                      <ExternalLink size={16} aria-hidden="true" />
                    </a>
                  </Alert>
                )}
            </>
          )}
          {error && (
            <Failure
              error={error}
              retry={() => {
                save.reset();
                check.reset();
                void updates.refetch();
              }}
            />
          )}
          <div className="action-row">
            <Button
              loading={check.isPending || updates.data?.checking}
              disabled={save.isPending || updates.isPending}
              title={t("applicationVersion.checkHelp")}
              onClick={() => check.mutate()}
            >
              {t("applicationVersion.check")}
            </Button>
          </div>
        </section>
      )}
      <section className="panel settings-section about-section">
        <h2>{t("applicationVersion.links")}</h2>
        <div className="about-link-group">
          <p>{t("settings.repository")}</p>
          <a
            className="back-link"
            href="https://github.com/rlibanez/eplsync"
            target="_blank"
            rel="noopener noreferrer"
          >
            https://github.com/rlibanez/eplsync{" "}
            <ExternalLink size={16} aria-hidden="true" />
          </a>
        </div>
        <div className="about-link-group">
          <p>ePubLibre</p>
          <a
            className="back-link"
            href="https://www.epublibre.org/"
            target="_blank"
            rel="noopener noreferrer"
          >
            https://www.epublibre.org/{" "}
            <ExternalLink size={16} aria-hidden="true" />
          </a>
        </div>
      </section>
    </>
  );
}
