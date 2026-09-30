import { createContext, useContext, useState, type ReactNode } from "react";
const Context = createContext<{
  collapsed: boolean;
  setCollapsed: (value: boolean) => void;
} | null>(null);
export function PreferencesProvider({ children }: { children: ReactNode }) {
  const [collapsed, update] = useState(() => {
    try {
      return localStorage.getItem("eplsync:sidebar-collapsed") === "true";
    } catch {
      return false;
    }
  });
  function setCollapsed(value: boolean) {
    update(value);
    try {
      localStorage.setItem("eplsync:sidebar-collapsed", String(value));
    } catch {
      /* Preference still applies to this session. */
    }
  }
  return (
    <Context.Provider value={{ collapsed, setCollapsed }}>
      {children}
    </Context.Provider>
  );
}
export function usePreferences() {
  const value = useContext(Context);
  if (!value) throw new Error("PreferencesProvider is required");
  return value;
}
