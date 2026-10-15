import react from "@vitejs/plugin-react";
import { defineConfig } from "vite";

// В разработке API — на uvicorn (порт 8000); в демо сервер сам раздаёт собранную панель.
export default defineConfig({
  plugins: [react()],
  build: { chunkSizeWarningLimit: 800 }, // Recharts крупный; для демо одного файла достаточно
  server: {
    proxy: { "/api": "http://127.0.0.1:8000" },
  },
});
