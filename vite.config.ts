import { defineConfig } from "vite";
import vue from "@vitejs/plugin-vue";

export default defineConfig({
  root: "web",
  plugins: [vue()],
  server: {
    port: 5173,
    strictPort: true,
    proxy: {
      "^/assets/": {
        target: process.env.API_PROXY_TARGET || "http://127.0.0.1:3100",
        changeOrigin: false,
      },
      "^/api/": {
        target: process.env.API_PROXY_TARGET || "http://127.0.0.1:3100",
        changeOrigin: false,
      },
    },
  },
  build: { outDir: "../dist", emptyOutDir: true },
});
