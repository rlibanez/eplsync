import { defineConfig } from "@playwright/test";
import development from "./playwright.config";
export default defineConfig({
  ...development,
  testIgnore: [],
  testMatch: ["**/lazy-navigation.spec.ts", "**/favicon.spec.ts"],
  use: { ...development.use, baseURL: "http://127.0.0.1:5179" },
  webServer: {
    command: "npm run build && npm run preview -- --port 5179 --strictPort",
    url: "http://127.0.0.1:5179",
    reuseExistingServer: false,
  },
});
