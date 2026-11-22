import { Alert } from "@mantine/core";
import { useTranslation } from "react-i18next";
import { useAuth } from "../auth/Auth";
import { Catalog } from "./Catalog";
export function Discover() {
  const { can } = useAuth();
  const { t } = useTranslation();
  return can("CATALOG_READ") && (can("TORRENT_SYNC") || can("CALIBRE_READ")) ? (
    <Catalog discovery />
  ) : (
    <Alert color="red">{t("auth.forbidden")}</Alert>
  );
}
