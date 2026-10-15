import { Tooltip } from "@mantine/core";
import { useClickOutside } from "@mantine/hooks";
import { useState } from "react";

export function PageTitle({
  title,
  description,
}: {
  title: string;
  description: string;
}) {
  const [opened, setOpened] = useState(false);
  const ref = useClickOutside<HTMLHeadingElement>(() => setOpened(false));
  return (
    <Tooltip
      label={description}
      opened={opened}
      multiline
      w={360}
      maw="calc(100vw - 32px)"
      withArrow
      position="bottom-start"
    >
      <h1
        ref={ref}
        className="page-title"
        tabIndex={0}
        onMouseEnter={() => setOpened(true)}
        onMouseLeave={() => setOpened(false)}
        onFocus={() => setOpened(true)}
        onBlur={() => setOpened(false)}
        onClick={() => setOpened(true)}
        onKeyDown={(event) => {
          if (event.key === "Escape") setOpened(false);
        }}
      >
        {title}
      </h1>
    </Tooltip>
  );
}
