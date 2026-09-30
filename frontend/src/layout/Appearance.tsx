import {
  createContext,
  useContext,
  useLayoutEffect,
  useState,
  type ReactNode,
} from "react";
import { MantineProvider, createTheme } from "@mantine/core";
export const palettes = {
  teal: 150,
  blue: 215,
  violet: 265,
  rose: 335,
  orange: 30,
  cyan: 185,
  indigo: 240,
  grape: 285,
  lime: 85,
  yellow: 48,
} as const;
export type Palette = keyof typeof palettes;
const Context = createContext<{
  scheme: "light" | "dark";
  setScheme: (value: "light" | "dark") => void;
  palette: Palette;
  setPalette: (value: Palette) => void;
} | null>(null);
export function AppearanceProvider({ children }: { children: ReactNode }) {
  const [scheme, setMode] = useState<"light" | "dark">(() => {
    try {
      return localStorage.getItem("eplsync:scheme") === "light"
        ? "light"
        : "dark";
    } catch {
      return "dark";
    }
  });
  function setScheme(value: "light" | "dark") {
    setMode(value);
    try {
      localStorage.setItem("eplsync:scheme", value);
    } catch {
      /* Optional storage. */
    }
  }
  const [palette, setValue] = useState<Palette>(() => {
    try {
      const saved = localStorage.getItem("eplsync:palette");
      return saved && Object.hasOwn(palettes, saved)
        ? (saved as Palette)
        : "teal";
    } catch {
      return "teal";
    }
  });
  function setPalette(value: Palette) {
    setValue(value);
    try {
      localStorage.setItem("eplsync:palette", value);
    } catch {
      /* Applies for this session. */
    }
  }
  useLayoutEffect(() => {
    document.documentElement.style.setProperty(
      "--hue",
      String(palettes[palette]),
    );
    document.documentElement.dataset.palette = palette;
  }, [palette]);
  return (
    <Context.Provider value={{ palette, setPalette, scheme, setScheme }}>
      <MantineProvider
        forceColorScheme={scheme}
        theme={createTheme({
          primaryColor: palette === "rose" ? "pink" : palette,
          fontFamily: "Inter, system-ui, sans-serif",
          defaultRadius: "md",
          autoContrast: true,
        })}
      >
        {children}
      </MantineProvider>
    </Context.Provider>
  );
}
export function useAppearance() {
  const value = useContext(Context);
  if (!value) throw new Error("AppearanceProvider is required");
  return value;
}
