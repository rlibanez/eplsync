import { useState, useEffect } from "react";
import { useTranslation } from "react-i18next";
import {
  ActionIcon,
  Button,
  Checkbox,
  Group,
  NumberInput,
} from "@mantine/core";
import { ArrowUp, ArrowDown, GripVertical } from "lucide-react";
import { Failure, Loading } from "../../components/Feedback";
import {
  defaultHomePreferences,
  sectionLabel,
  useHomePreferences,
  useSaveHomePreferences,
  type HomePreferences,
  type HomeSectionId,
} from "../home/homePreferences";

export function HomeSettings() {
  const result = useHomePreferences();
  if (result.isPending) return <Loading />;
  if (result.isError)
    return <Failure error={result.error} retry={() => result.refetch()} />;
  return <HomeSettingsForm initial={result.data} />;
}
function HomeSettingsForm({ initial }: { initial: HomePreferences }) {
  const { t } = useTranslation();
  const [draft, setDraft] = useState(initial);
  useEffect(() => setDraft(initial), [initial]);
  const [dragged, setDragged] = useState<HomeSectionId | null>(null);
  const save = useSaveHomePreferences();
  function move(id: HomeSectionId, to: number) {
    setDraft((current) => {
      const sections = [...current.sections];
      const from = sections.findIndex((section) => section.id === id);
      if (to < 0 || to >= sections.length || from < 0) return current;
      const [section] = sections.splice(from, 1);
      sections.splice(to, 0, section);
      return { sections };
    });
  }
  return (
    <section className="panel settings-section">
      <h2>{t("homeSettings.sections")}</h2>
      <p>{t("homeSettings.help")}</p>
      <form
        onSubmit={(event) => {
          event.preventDefault();
          save.mutate(draft);
        }}
      >
        <ol className="home-section-settings">
          {draft.sections.map((section, index) => (
            <li
              key={section.id}
              onDragOver={(event) => {
                if (dragged) event.preventDefault();
              }}
              onDrop={(event) => {
                event.preventDefault();
                if (dragged) move(dragged, index);
                setDragged(null);
              }}
            >
              <span
                className="home-section-grip"
                draggable={!save.isPending}
                onDragStart={(event) => {
                  setDragged(section.id);
                  event.dataTransfer.effectAllowed = "move";
                  event.dataTransfer.setData("text/plain", section.id);
                }}
                onDragEnd={() => setDragged(null)}
                title={t("homeSettings.drag")}
              >
                <GripVertical size={20} />
              </span>
              <Checkbox
                label={t(sectionLabel(section.id))}
                checked={section.enabled}
                disabled={save.isPending}
                onChange={(event) => {
                  const enabled = event.currentTarget.checked;
                  setDraft((current) => ({
                    sections: current.sections.map((item) =>
                      item.id === section.id ? { ...item, enabled } : item,
                    ),
                  }));
                }}
              />
              {(section.bookCount !== null ||
                section.id === "recentEvents") && (
                <NumberInput
                  label={t(
                    section.id === "recentEvents"
                      ? "homeSettings.eventCount"
                      : "homeSettings.bookCount",
                  )}
                  value={
                    section.id === "recentEvents"
                      ? (section.eventCount ?? 10)
                      : (section.bookCount ?? 10)
                  }
                  min={1}
                  max={100}
                  allowDecimal={false}
                  allowNegative={false}
                  required
                  withAsterisk={false}
                  disabled={save.isPending}
                  onChange={(value) => {
                    setDraft((current) => ({
                      sections: current.sections.map((item) =>
                        item.id === section.id
                          ? {
                              ...item,
                              [section.id === "recentEvents"
                                ? "eventCount"
                                : "bookCount"]:
                                typeof value === "number" ? value : 0,
                            }
                          : item,
                      ),
                    }));
                  }}
                />
              )}
              <Group gap="xs" className="home-section-move">
                <ActionIcon
                  variant="subtle"
                  disabled={index === 0 || save.isPending}
                  aria-label={t("homeSettings.moveUp", {
                    section: t(sectionLabel(section.id)),
                  })}
                  onClick={() => move(section.id, index - 1)}
                >
                  <ArrowUp size={18} />
                </ActionIcon>
                <ActionIcon
                  variant="subtle"
                  disabled={
                    index === draft.sections.length - 1 || save.isPending
                  }
                  aria-label={t("homeSettings.moveDown", {
                    section: t(sectionLabel(section.id)),
                  })}
                  onClick={() => move(section.id, index + 1)}
                >
                  <ArrowDown size={18} />
                </ActionIcon>
              </Group>
            </li>
          ))}
        </ol>
        <Group mt="lg">
          <Button
            type="submit"
            loading={save.isPending}
            disabled={draft.sections.some(
              (section) =>
                (section.bookCount !== null &&
                  (section.bookCount < 1 || section.bookCount > 100)) ||
                (section.id === "recentEvents" &&
                  ((section.eventCount ?? 10) < 1 ||
                    (section.eventCount ?? 10) > 100)),
            )}
          >
            {t("homeSettings.save")}
          </Button>
          <Button
            variant="default"
            disabled={save.isPending}
            onClick={() => {
              setDraft(defaultHomePreferences());
              save.reset();
            }}
          >
            {t("homeSettings.restore")}
          </Button>
        </Group>
        {save.isError && (
          <Failure error={save.error} retry={() => save.mutate(draft)} />
        )}
        {save.isSuccess && <p role="status">{t("homeSettings.saved")}</p>}
      </form>
    </section>
  );
}
