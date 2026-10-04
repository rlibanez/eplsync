import { ServerSettings } from "./ServerSettings";
import { NotificationSettings } from "../notifications/NotificationSettings";
import { EventMaintenance } from "../events/EventMaintenance";
import { useTranslation } from "react-i18next";
import { palettes, useAppearance, type Palette } from "../../layout/Appearance";
import { LanguagePicker } from "../../components/LanguagePicker";
export function Settings() {
  const { t } = useTranslation();
  const { palette, setPalette, scheme, setScheme } = useAppearance();
  return (
    <>
      <section className="panel settings-section">
        <h2>{t("settings.language")}</h2>
        <p className="muted">{t("settings.languageDescription")}</p>
        <LanguagePicker />
      </section>
      <section className="panel settings-section">
        <h2>{t("settings.appearance")}</h2>
        <fieldset className="palette-options scheme-options">
          <legend>{t("settings.scheme")}</legend>
          {(["light", "dark"] as const).map((value) => (
            <label key={value}>
              <input
                type="radio"
                name="scheme"
                checked={scheme === value}
                onChange={() => setScheme(value)}
              />
              {t(`settings.${value}`)}
            </label>
          ))}
        </fieldset>
        <h3>{t("settings.palette")}</h3>
        <p className="muted">{t("settings.paletteDescription")}</p>
        <fieldset className="palette-options">
          <legend className="sr-only">{t("settings.palette")}</legend>
          {(Object.keys(palettes) as Palette[]).map((value) => (
            <label key={value}>
              <input
                type="radio"
                name="palette"
                value={value}
                checked={palette === value}
                onChange={() => setPalette(value)}
              />
              <span
                className="color-swatch"
                style={{ background: `hsl(${palettes[value]} 55% 65%)` }}
                aria-hidden="true"
              />
              {t(`settings.colors.${value}`)}
            </label>
          ))}
        </fieldset>
      </section>
      <NotificationSettings />
      <ServerSettings section="events" />
      <EventMaintenance />
    </>
  );
}
