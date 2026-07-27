import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';

// Dev server proxies /api and /actuator to the kb-rag-server backend (default port 20000),
// see kb-rag-deploy/docs/M1-CONTRACTS.md section 0.
const BACKEND_ORIGIN = 'http://127.0.0.1:20000';

export default defineConfig({
  plugins: [react()],
  server: {
    // Bind dual-stack. Vite's default host is `localhost`, which Node 18+ resolves to
    // ::1 only — 127.0.0.1:20002 is then unreachable, breaking IPv4-only clients and
    // any tooling that does not follow the IPv6 loopback. '::' accepts both families
    // via IPv4-mapped addresses. Trade-off: this also binds every interface, so the
    // dev server is reachable from the LAN.
    host: '::',
    port: 20002,
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
