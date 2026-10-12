import { useTranslation } from "react-i18next";
import { useNotifications } from "../notifications/Notifications";

export function useSettingsSaved() {
  const { t } = useTranslation();
  const { notify } = useNotifications();
  return () => notify({ title: t("nav.settings"), message: t("serverSettings.saved"), tone: "success" });
}
