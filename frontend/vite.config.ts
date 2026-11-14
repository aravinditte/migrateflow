import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';

// Dev: proxy /api to the backend so session + CSRF cookies behave exactly
// as in the same-origin production deployment (spec 7.1).
export default defineConfig({
  plugins: [react()],
  server: {
    port: 5173,
    proxy: {
      '/api': 'http://localhost:8081',
      '/actuator': 'http://localhost:8081',
    },
  },
});
