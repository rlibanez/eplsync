import { useState } from "react";
import { BookOpen } from "lucide-react";
import { CoverPreview } from "../../components/CoverPreview";
import { useTranslation } from "react-i18next";

function safeCover(value?: string | null) {
  if (!value) return undefined;
  try {
    const url = new URL(value, window.location.origin);
    if (url.username || url.password) return undefined;
    if (
      url.origin === window.location.origin &&
      url.pathname === "/api/calibre/hardcover/cover"
    )
      return url.href;
    if (
      url.protocol === "https:" &&
      url.hostname === "assets.hardcover.app" &&
      (!url.port || url.port === "443")
    )
      return url.href;
  } catch {
    /* Missing or invalid covers use the same placeholder. */
  }
  return undefined;
}

export function HardcoverCover({
  url,
  label,
  onExpandedChange,
}: {
  url?: string | null;
  label: string;
  onExpandedChange: (expanded: boolean) => void;
}) {
  const { t } = useTranslation();
  const [expanded, setExpanded] = useState(false);
  const toggle = (value: boolean) => {
    setExpanded(value);
    onExpandedChange(value);
  };
  const [failed, setFailed] = useState<string>();
  const source = safeCover(url);
  const available = source && source !== failed;
  const image = available ? (
    <img
      src={source}
      alt={label}
      loading="lazy"
      decoding="async"
      referrerPolicy="no-referrer"
      onError={() => {
        setFailed(source);
        if (expanded) toggle(false);
      }}
    />
  ) : (
    <span role="img" aria-label={label}>
      <BookOpen size={22} aria-hidden="true" />
    </span>
  );
  return (
    <>
      {available ? (
        <button
          type="button"
          className="hardcover-cover hardcover-cover-button"
          title={t("hardcover.enlargeCover", { cover: label })}
          aria-label={t("hardcover.enlargeCover", { cover: label })}
          onClick={() => toggle(true)}
        >
          {image}
        </button>
      ) : (
        <span className="hardcover-cover" title={label}>
          {image}
        </span>
      )}
      <CoverPreview
        source={available ? source : undefined}
        label={label}
        opened={expanded}
        onClose={() => toggle(false)}
        onError={() => {
          setFailed(source);
          if (expanded) toggle(false);
        }}
      />
    </>
  );
}
