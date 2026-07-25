import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';

// Dev server proxies /api and /actuator to the kb-rag-server backend (default port 8080),
// see kb-rag-deploy/docs/M1-CONTRACTS.md section 0.
const BACKEND_ORIGIN = 'http://127.0.0.1:8080';

export default defineConfig({
  plugins: [react()],
  server: {
    port: 5173,
    proxy: {
      '/api': {
        target: BACKEND_ORIGIN,
        changeOrigin: true,
      },
      '/actuator': {
        target: BACKEND_ORIGIN,
        changeOrigin: true,
      },
    },
  },
});
