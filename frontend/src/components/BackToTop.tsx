import { useEffect, useState } from "react";
import { ActionIcon } from "@mantine/core";
import { ArrowUp } from "lucide-react";
import { useTranslation } from "react-i18next";
import { useLocation } from "react-router-dom";
export function BackToTop({ hidden = false }: { hidden?: boolean }) {
  const { t } = useTranslation();
  const { key } = useLocation();
  const [visible, setVisible] = useState(false);
  useEffect(() => {
    const update = () =>
      setVisible(
        window.scrollY > Math.min(400, window.innerHeight / 2) &&
          document.documentElement.scrollHeight > window.innerHeight,
      );
    update();
    window.addEventListener("scroll", update, { passive: true });
    window.addEventListener("resize", update);
    const observer = new ResizeObserver(update);
    observer.observe(document.body);
    return () => {
      window.removeEventListener("scroll", update);
      window.removeEventListener("resize", update);
      observer.disconnect();
    };
  }, [key]);
  if (!visible || hidden) return null;
  return (
    <ActionIcon
      className="back-to-top"
      size={44}
      radius="xl"
      aria-label={t("nav.backToTop")}
      title={t("nav.backToTop")}
      onClick={() => {
        document.getElementById("main")?.focus({ preventScroll: true });
        window.scrollTo({
          top: 0,
          behavior: window.matchMedia("(prefers-reduced-motion: reduce)")
            .matches
            ? "instant"
            : "smooth",
        });
      }}
    >
      <ArrowUp size={22} />
    </ActionIcon>
  );
}
