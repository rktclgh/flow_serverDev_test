import react from '@vitejs/plugin-react'
import { defineConfig } from 'vite'

// https://vite.dev/config/
export default defineConfig({
  plugins: [react()],
  server: {
    // 개발 중에는 Vite(5173)와 Spring(8080)이 분리되어 있다.
    // 프록시로 동일 출처처럼 만들어 CORS 설정을 아예 두지 않는다.
    // 배포 시에는 dist/ 가 Spring 의 static 리소스로 번들되므로 이 설정은 쓰이지 않는다.
    proxy: {
      '/api': {
        target: 'http://localhost:8080',
        changeOrigin: true,
      },
      '/health': {
        target: 'http://localhost:8080',
        changeOrigin: true,
      },
    },
  },
})
