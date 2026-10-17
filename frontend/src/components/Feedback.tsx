import { ApiError, NetworkError } from "../api/catalog";
import { ActionError } from "../features/downloads/shared";
import { useTranslation } from "react-i18next";
import type { ReactNode } from "react";
import { Alert, Button, Group, Loader } from "@mantine/core";
export function canRetry(error: Error): boolean {
  if (error instanceof ApiError || error instanceof ActionError) {
    return (
      error.status === 0 ||
      [408, 409, 425, 429].includes(error.status) ||
      (error.status >= 500 && ![501, 505].includes(error.status))
    );
  }
  return true;
}
export function Loading() {
  const { t } = useTranslation();
  return (
    <div className="feedback" role="status">
      <Loader size="sm" />
      <p>{t("feedback.loading")} </p>
    </div>
  );
}
export function Failure({
  error,
  retry,
  notFoundKey = "errors.notFound",
  actions,
}: {
  error: Error;
  retry: () => void;
  notFoundKey?: string;
  actions?: ReactNode;
}) {
  const { t } = useTranslation();
  const responseError =
    error instanceof ApiError || error instanceof ActionError;
  return (
    <Alert
      color="red"
      title={responseError && error.details ? undefined : t("feedback.title")}
      role="alert"
    >
      <p>
        {responseError && [413, 414, 431].includes(error.status)
          ? t("errors.requestTooLarge")
          : responseError && error.details
            ? error.details
            : responseError && error.status !== 0
              ? t(
                  error.status === 404
                    ? notFoundKey
                    : error.status === 400
                      ? "errors.invalidRequest"
                      : "errors.http",
                  {
                    status: error.status,
                  },
                )
              : t(
                  error instanceof NetworkError ||
                    (error instanceof ActionError && error.status === 0)
                    ? "errors.network"
                    : "errors.unexpected",
                )}
      </p>
      <Group gap="sm">
        {canRetry(error) && (
          <Button variant="light" color="red" onClick={retry}>
            {t("feedback.retry")}{" "}
          </Button>
        )}
        {actions}
      </Group>
    </Alert>
  );
}
