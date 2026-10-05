import { secureFetch } from "../auth/transport";
import { useState } from "react";
import { Button, Pagination } from "@mantine/core";
import { Trash2 } from "lucide-react";
import { useTranslation } from "react-i18next";
import { useQueryClient } from "@tanstack/react-query";
import { AppModal, ModalActions } from "../../components/AppModal";
import { ApiError } from "../../api/catalog";
import { useNotifications } from "../notifications/Notifications";
interface Preview {
  token: string;
  total: number;
  page: number;
  size: number;
  items: { eplId: number; title: string; revision: number }[];
}
export function MissingBooks({ disabled }: { disabled: boolean }) {
  const { t } = useTranslation();
  const cache = useQueryClient();
  const { notify } = useNotifications();
  const [preview, setPreview] = useState<Preview | null>(null);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  async function request(path: string, body?: object, method = "POST") {
    const response = await secureFetch(`/api/catalog/import/missing/${path}`, {
      method,
      headers: {
        Accept: "application/json",
        "Content-Type": "application/json",
      },
      ...(body ? { body: JSON.stringify(body) } : {}),
    });
    if (!response.ok) throw new ApiError(response.status);
    return response.json();
  }
  async function perform(action: "preview" | "delete" | "page", page = 0) {
    if (busy) return;
    setBusy(true);
    setError(null);
    try {
      if (action === "preview") setPreview(await request("preview"));
      else if (action === "page" && preview)
        setPreview(
          await request(
            `${preview.token}?page=${page}&size=50`,
            undefined,
            "GET",
          ),
        );
      else if (action === "delete" && preview) {
        await request("delete", { token: preview.token, confirm: true });
        setPreview(null);
        for (const key of [
          "catalog",
          "book",
          "directory",
          "catalog-metadata",
          "downloads",
        ])
          void cache.invalidateQueries({ queryKey: [key] });
      }
    } catch (error) {
      const message = t(
        error instanceof ApiError && error.status === 409
          ? "missing.conflict"
          : "missing.error",
      );
      setError(message);
      if (!preview)
        notify({ title: t("missing.title"), message, tone: "error" });
    } finally {
      setBusy(false);
    }
  }
  return (
    <>
      <Button
        variant="default"
        leftSection={<Trash2 size={17} />}
        disabled={disabled || busy}
        loading={busy && !preview}
        onClick={() => void perform("preview")}
      >
        {t("missing.title")}
      </Button>
      <AppModal
        opened={preview !== null}
        onClose={() => {
          if (!busy) setPreview(null);
        }}
        closeOnClickOutside={!busy}
        closeOnEscape={!busy}
        withCloseButton={!busy}
        title={t("missing.title")}
        icon={Trash2}
        size="lg"
        centered
      >
        {preview && preview.total > 0 ? (
          <>
            <p>{t("missing.description", { count: preview.total })}</p>
            <p className="muted">{t("missing.kept")}</p>
            <div className="missing-books-list">
              <table>
                <thead>
                  <tr>
                    <th>{t("filters.eplId")}</th>
                    <th>{t("missing.bookTitle")}</th>
                    <th>{t("missing.revision")}</th>
                  </tr>
                </thead>
                <tbody>
                  {preview.items.map((book) => (
                    <tr key={book.eplId}>
                      <td>{book.eplId}</td>
                      <td>{book.title}</td>
                      <td>{book.revision}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
            {preview.total > preview.size && (
              <Pagination
                size="sm"
                disabled={busy}
                total={Math.ceil(preview.total / preview.size)}
                value={preview.page + 1}
                onChange={(page) => void perform("page", page - 1)}
              />
            )}
          </>
        ) : (
          <p>{t("missing.empty")}</p>
        )}
        {error && <p role="alert">{error}</p>}
        <ModalActions>
          <Button
            variant="default"
            disabled={busy}
            onClick={() => setPreview(null)}
          >
            {t("import.cancel")}
          </Button>
          <Button
            color="red"
            disabled={!preview?.total || busy || !!error}
            loading={busy}
            onClick={() => void perform("delete")}
          >
            {t("missing.confirm")}
          </Button>
        </ModalActions>
      </AppModal>
    </>
  );
}
