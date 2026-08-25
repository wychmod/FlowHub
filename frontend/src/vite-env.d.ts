/// <reference types="vite/client" />

interface ImportMetaEnv {
  /**
   * 后端 API 基础地址。留空（默认）时同源请求走 Vite 代理到 8080；
   * 如需直连后端，可在 .env.local 中配置为 http://localhost:8080
   * （后端 WebConfig 已放行 5174 跨域）。
   */
  readonly VITE_API_BASE_URL?: string;
}

interface ImportMeta {
  readonly env: ImportMetaEnv;
}
