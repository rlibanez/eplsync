import {
  useEffect,
  useRef,
  useState,
  type KeyboardEvent,
  type ReactNode,
} from "react";
import { Combobox, PillsInput, Pill, useCombobox, Button } from "@mantine/core";
import { useDebouncedValue } from "@mantine/hooks";
import { useInfiniteQuery } from "@tanstack/react-query";
import { useTranslation } from "react-i18next";
import { get } from "../api/catalog";

export type SuggestionKind = "titles" | "authors" | "collections" | "genres";
type Result = { items: string[]; total: number; nextOffset: number | null };

export function SuggestionInput({
  kind,
  label,
  value,
  onChange,
  onSelect,
  onKeyDown,
  error,
  children,
  excluded = [],
  name,
  maxLength,
}: {
  kind?: SuggestionKind;
  label: string;
  value: string;
  onChange: (value: string) => void;
  onSelect: (value: string) => void;
  onKeyDown?: (event: KeyboardEvent<HTMLInputElement>) => void;
  error?: string;
  children?: ReactNode;
  excluded?: string[];
  name?: string;
  maxLength?: number;
}) {
  const { t } = useTranslation();
  const [focused, setFocused] = useState(false);
  const [composing, setComposing] = useState(false);
  const [dismissed, setDismissed] = useState(false);
  const [debounced] = useDebouncedValue(value.trim(), 275);
  const scroll = useRef<HTMLDivElement>(null);
  const enabled =
    !!kind &&
    focused &&
    !composing &&
    !dismissed &&
    debounced.length >= 2 &&
    debounced === value.trim();
  const result = useInfiniteQuery({
    queryKey: ["catalog", "suggestions", kind, debounced],
    initialPageParam: 0,
    queryFn: ({ pageParam, signal }) =>
      get<Result>(
        `/catalog/suggestions/${kind}?${new URLSearchParams({ q: debounced, offset: String(pageParam) })}`,
        signal,
      ),
    getNextPageParam: (last) => last.nextOffset ?? undefined,
    enabled,
    staleTime: 0,
    gcTime: 60_000,
    refetchOnWindowFocus: false,
    retry: false,
  });
  const opened = enabled;
  const combobox = useCombobox({
    opened,
    loop: false,
    onDropdownClose: () => setDismissed(true),
  });
  const items = [
    ...new Set(result.data?.pages.flatMap((page) => page.items) ?? []),
  ].filter((item) => !excluded.includes(item));
  useEffect(() => {
    combobox.resetSelectedOption();
    if (scroll.current) scroll.current.scrollTop = 0;
  }, [value]); // Reset only for edited text, never when appending another page.
  const more = () => {
    if (
      result.hasNextPage &&
      !result.isFetching &&
      !result.isFetchNextPageError
    )
      void result.fetchNextPage();
  };
  useEffect(() => {
    const node = scroll.current;
    if (opened && node && node.scrollHeight <= node.clientHeight + 2) more();
  }, [opened, items.length, result.isFetching, result.hasNextPage]);
  function select(option: string) {
    onSelect(option);
    setDismissed(true);
    combobox.resetSelectedOption();
  }
  return (
    <Combobox store={combobox} onOptionSubmit={select} withinPortal>
      <Combobox.DropdownTarget>
        <PillsInput
          label={label}
          error={error}
          onClick={() => {
            setFocused(true);
          }}
        >
          <Pill.Group>{renderField()}</Pill.Group>
        </PillsInput>
      </Combobox.DropdownTarget>
      <Combobox.Dropdown>
        <Combobox.Options
          ref={scroll}
          style={{
            maxHeight: 260,
            overflowY: "auto",
            overscrollBehavior: "contain",
          }}
          onScroll={(e) => {
            const el = e.currentTarget;
            if (el.scrollHeight - el.scrollTop - el.clientHeight < 70) more();
          }}
        >
          {items.map((item) => (
            <Combobox.Option key={item} value={item}>
              {item}
            </Combobox.Option>
          ))}
          {result.isFetching && (
            <Combobox.Empty>{t("suggestions.loading")}</Combobox.Empty>
          )}
          {!result.isFetching && !result.isError && items.length === 0 && (
            <Combobox.Empty>{t("suggestions.empty")}</Combobox.Empty>
          )}
          {result.isError && (
            <Combobox.Empty>
              {t("suggestions.failed")}
              <Button
                variant="subtle"
                size="compact-sm"
                onMouseDown={(e) => e.preventDefault()}
                onClick={() =>
                  result.isFetchNextPageError
                    ? void result.fetchNextPage()
                    : void result.refetch()
                }
              >
                {t("suggestions.retry")}
              </Button>
            </Combobox.Empty>
          )}
          {result.hasNextPage && !result.isFetching && !result.isError && (
            <Button
              fullWidth
              variant="subtle"
              size="compact-sm"
              onMouseDown={(e) => e.preventDefault()}
              onClick={more}
            >
              {t("suggestions.more")}
            </Button>
          )}
        </Combobox.Options>
      </Combobox.Dropdown>
    </Combobox>
  );

  function renderField() {
    return (
      <>
        {children}
        <Combobox.EventsTarget>
          <PillsInput.Field
            name={name}
            maxLength={maxLength}
            value={value}
            onChange={(e) => {
              setDismissed(false);
              onChange(e.currentTarget.value);
            }}
            onFocus={() => {
              setFocused(true);
              setDismissed(false);
            }}
            onBlur={() => {
              setFocused(false);
              combobox.resetSelectedOption();
            }}
            onCompositionStart={() => setComposing(true)}
            onCompositionEnd={() => setComposing(false)}
            onKeyDown={(e) => {
              if (e.nativeEvent.isComposing || composing) return;
              if (e.key === "Escape") {
                setDismissed(true);
                combobox.resetSelectedOption();
                return;
              }
              if (
                opened &&
                combobox.getSelectedOptionIndex() !== -1 &&
                e.key === "Enter"
              )
                return;
              if (
                opened &&
                combobox.getSelectedOptionIndex() !== -1 &&
                e.key === "Tab" &&
                !e.shiftKey
              ) {
                e.preventDefault();
                combobox.clickSelectedOption();
                return;
              }
              if (e.key === "Enter" || e.key === "Tab") setDismissed(true);
              onKeyDown?.(e);
            }}
          />
        </Combobox.EventsTarget>
      </>
    );
  }
}
