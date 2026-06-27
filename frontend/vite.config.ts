import { defineConfig } from "vite";
import react from "@vitejs/plugin-react-swc";

// The build output goes straight into the Ktor resources dir so the fat jar
// bundles UI + API in a single artifact. Dev mode proxies /api to :8080.
export default defineConfig({
  plugins: [react()],
  build: {
    outDir: "../src/main/resources/static",
    emptyOutDir: true,
  },
  server: {
    port: 5173,
    proxy: {
      "/api": "http://localhost:8080",
    },
  },
});
