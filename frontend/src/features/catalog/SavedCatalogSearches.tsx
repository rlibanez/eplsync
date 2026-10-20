import { useRef, useState } from "react";
import { Button, Menu, Modal, Select, TextInput, Alert } from "@mantine/core";
import { useQuery, useQueryClient } from "@tanstack/react-query";
import { useTranslation } from "react-i18next";
import { authRequest } from "../auth/transport";
import { filterKeys } from "./CatalogFilters";

export type SavedSearch = {
  id: string;
  name: string;
  filters: Record<string, string[]>;
};
export function savedFilters(params: URLSearchParams) {
  return Object.fromEntries(
    filterKeys
      .filter((key) => params.has(key))
      .map((key) => [key, params.getAll(key)]),
  );
}
export function applySavedFilters(
  current: URLSearchParams,
  filters: SavedSearch["filters"],
) {
  const next = new URLSearchParams(current);
  filterKeys.forEach((key) => next.delete(key));
  filterKeys.forEach((key) =>
    filters[key]?.forEach((value) => next.append(key, value)),
  );
  next.set("page", "0");
  return next;
}
export function SavedCatalogSearches({
  selected,
  onSelect,
  capture,
  apply,
}: {
  selected: string | null;
  onSelect: (id: string | null) => void;
  capture: () => URLSearchParams | null;
  apply: (filters: SavedSearch["filters"]) => void;
}) {
  const { t } = useTranslation();
  const client = useQueryClient();
  const query = useQuery({
    queryKey: ["catalog-saved-searches"],
    queryFn: () => authRequest<SavedSearch[]>("/catalog/saved-searches"),
  });
  const current = query.data?.find((item) => item.id === selected);
  const [mode, setMode] = useState<"create" | "rename" | "delete" | null>(null);
  const lastMode = useRef<"create" | "rename" | "delete">("create");
  if (mode) lastMode.current = mode;
  const modalMode = mode ?? lastMode.current;
  const [name, setName] = useState("");
  const [pending, setPending] = useState(false);
  const [error, setError] = useState("");
  async function mutate(action: "create" | "rename" | "delete" | "update") {
    const params =
      action === "create" || action === "update" ? capture() : null;
    if ((action === "create" || action === "update") && !params) {
      setError(t("filters.invalidValue"));
      return;
    }
    setPending(true);
    setError("");
    try {
      const result = await authRequest<SavedSearch>(
        `/catalog/saved-searches${action === "create" ? "" : `/${current!.id}`}`,
        action === "delete" ? "DELETE" : action === "create" ? "POST" : "PUT",
        action === "delete"
          ? undefined
          : {
              name: action === "update" ? current!.name : name,
              filters: params ? savedFilters(params) : current!.filters,
            },
      );
      onSelect(action === "delete" ? null : result.id);
      await client.invalidateQueries({ queryKey: ["catalog-saved-searches"] });
      setMode(null);
    } catch (cause) {
      setError(cause instanceof Error ? cause.message : String(cause));
    } finally {
      setPending(false);
    }
  }
  return (
    <div className="saved-catalog-searches">
      <div className="action-row">
        <Select
          aria-label={t("savedSearches.heading")}
          placeholder={t("savedSearches.heading")}
          clearable
          clearButtonProps={{
            "aria-hidden": false,
            tabIndex: 0,
            "aria-label": `${t("catalog.clear")}: ${t("savedSearches.heading")}`,
          }}
          searchable
          value={selected}
          disabled={pending}
          allowDeselect={false}
          onChange={onSelect}
          onOptionSubmit={(id) => {
            onSelect(id);
            const search = query.data?.find((item) => item.id === id);
            if (search) apply(search.filters);
          }}
          data={(query.data ?? []).map((item) => ({
            value: item.id,
            label: item.name,
          }))}
        />
        <Button
          type="button"
          variant="default"
          disabled={pending}
          onClick={() => {
            setName("");
            setError("");
            setMode("create");
          }}
        >
          {t("savedSearches.save")}
        </Button>
        <Menu>
          <Menu.Target>
            <Button
              type="button"
              variant="subtle"
              disabled={!current || pending}
            >
              {t("savedSearches.manage")}
            </Button>
          </Menu.Target>
          <Menu.Dropdown>
            <Menu.Item onClick={() => void mutate("update")}>
              {t("savedSearches.update")}
            </Menu.Item>
            <Menu.Item
              onClick={() => {
                setName(current!.name);
                setError("");
                setMode("rename");
              }}
            >
              {t("savedSearches.rename")}
            </Menu.Item>
            <Menu.Item
              color="red"
              onClick={() => {
                setError("");
                setMode("delete");
              }}
            >
              {t("savedSearches.delete")}
            </Menu.Item>
          </Menu.Dropdown>
        </Menu>
      </div>
      {(query.isError || (error && !mode)) && (
        <Alert color="red">{error || t("savedSearches.loadError")}</Alert>
      )}
      <Modal
        opened={mode !== null}
        onClose={() => !pending && setMode(null)}
        title={t(
          `savedSearches.${modalMode === "create" ? "save" : modalMode === "rename" ? "rename" : "delete"}`,
        )}
      >
        {modalMode === "delete" ? (
          <p>{t("savedSearches.deleteConfirm", { name: current?.name })}</p>
        ) : (
          <TextInput
            label={t("savedSearches.name")}
            value={name}
            maxLength={120}
            onChange={(event) => setName(event.currentTarget.value)}
            autoFocus
          />
        )}
        {error && <Alert color="red">{error}</Alert>}
        <div className="action-row">
          <Button
            variant="default"
            disabled={pending}
            onClick={() => setMode(null)}
          >
            {t("common.cancel")}
          </Button>
          <Button
            color={mode === "delete" ? "red" : undefined}
            loading={pending}
            disabled={mode !== "delete" && !name.trim()}
            onClick={() => mode && void mutate(mode)}
          >
            {t(
              mode === "delete" ? "savedSearches.delete" : "savedSearches.save",
            )}
          </Button>
        </div>
      </Modal>
    </div>
  );
}
