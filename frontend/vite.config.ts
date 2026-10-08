import { defineConfig } from "vite";
import react from "@vitejs/plugin-react";
export default defineConfig({
  plugins: [react()],
  server: { proxy: { "/api": "http://localhost:8088" } },
  build: {
    rollupOptions: {
      output: {
        // Keep shared libraries independently cacheable; route modules remain lazy.
        manualChunks(id) {
          const path = id.replaceAll("\\", "/");
          if (!path.includes("/node_modules/")) return;
          const name = path.split("/node_modules/").at(-1)!;
          if (/^(react|react-dom|scheduler)\//.test(name)) return "framework";
          if (
            /^(@mantine|@floating-ui)\//.test(name) ||
            name.startsWith("react-number-format/")
          )
            return "ui";
          if (/^(react-router|react-router-dom)\//.test(name)) return "router";
          if (name.startsWith("@tanstack/")) return "query";
          if (/^(i18next|react-i18next)\//.test(name)) return "i18n";
        },
      },
    },
  },
});
