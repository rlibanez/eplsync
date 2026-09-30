import { useTranslation } from "react-i18next";
import { Link } from "react-router-dom";
import { ArrowRight, Library, Search, BookOpen } from "lucide-react";
export function Home() {
  const { t } = useTranslation();
  return (
    <>
      <div className="eyebrow">{t("home.welcome")} </div>
      <section className="hero">
        <div>
          <h1>
            {t("home.title")} <br />
            <span>{t("home.subtitle")} </span>
          </h1>
          <p>{t("home.description")} </p>
          <Link className="primary-link" to="/catalog">
            {t("nav.explore")} <ArrowRight size={18} />
          </Link>
        </div>
        <div className="book-art" aria-hidden="true">
          <div className="art-book one">
            EPL
            <span>{t("home.art")}</span>
            <BookOpen />
          </div>
          <div className="art-book two" />
          <div className="art-book three" />
        </div>
      </section>
      <section className="home-bottom">
        <div>
          <div className="eyebrow">{t("home.space")} </div>
          <h2>{t("home.heading")} </h2>
          <p className="muted">{t("home.intro")} </p>
        </div>
        <Link className="feature-card" to="/catalog">
          <Library />
          <h3>{t("home.browse")} </h3>
          <p>{t("home.browseDescription")} </p>
          <span>
            {t("home.books")} <ArrowRight size={16} />
          </span>
        </Link>
        <Link className="feature-card" to="/catalog">
          <Search />
          <h3>{t("home.find")} </h3>
          <p>{t("home.findDescription")} </p>
          <span>
            {t("home.search")} <ArrowRight size={16} />
          </span>
        </Link>
      </section>
    </>
  );
}
