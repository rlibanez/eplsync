import { ApiError, NetworkError } from "../api/catalog";
import { useTranslation } from "react-i18next";
import { Alert, Button, Loader } from "@mantine/core";
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
}: {
  error: Error;
  retry: () => void;
  notFoundKey?: string;
}) {
  const { t } = useTranslation();
  return (
    <Alert color="red" title={t("feedback.title")} role="alert">
      <p>
        {error instanceof ApiError
          ? t(error.status === 404 ? notFoundKey : "errors.http", {
              status: error.status,
            })
          : t(
              error instanceof NetworkError
                ? "errors.network"
                : "errors.unexpected",
            )}
      </p>
      <Button variant="light" color="red" onClick={retry}>
        {t("feedback.retry")}{" "}
      </Button>
    </Alert>
  );
}
