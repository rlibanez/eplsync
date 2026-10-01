import { useTranslation } from "react-i18next";
import { ExternalLink } from "lucide-react";
export function About() {
  const { t } = useTranslation();
  return (
    <section className="panel settings-section">
      <p>{t("settings.repository")}</p>
      <a className="back-link" href="https://github.com/rlibanez/eplsync" target="_blank" rel="noopener noreferrer">
        https://github.com/rlibanez/eplsync <ExternalLink size={16} aria-hidden="true" />
      </a>
      <p>ePubLibre</p>
      <a className="back-link" href="https://www.epublibre.org/" target="_blank" rel="noopener noreferrer">
        https://www.epublibre.org/ <ExternalLink size={16} aria-hidden="true" />
      </a>
    </section>
  );
}
