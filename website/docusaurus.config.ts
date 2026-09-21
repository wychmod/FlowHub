import { themes as prismThemes } from 'prism-react-renderer';
import type { Config } from '@docusaurus/types';
import type * as Preset from '@docusaurus/preset-classic';

const config: Config = {
  title: 'FlowHub',
  tagline: '企业级异步 Excel 导出中心 · Enterprise-Grade Asynchronous Export & Import Center',
  favicon: 'img/logo.svg',

  url: 'https://wychmod.github.io',
  baseUrl: '/FlowHub/',
  trailingSlash: false,

  organizationName: 'wychmod',
  projectName: 'FlowHub',

  // doc/ 复盘文档存在历史链接，当前仍降级为警告以免阻断内容构建。
  onBrokenLinks: 'warn',

  i18n: {
    defaultLocale: 'zh-CN',
    locales: ['zh-CN', 'en'],
    localeConfigs: {
      'zh-CN': {
        label: '中文',
        direction: 'ltr',
        htmlLang: 'zh-CN',
      },
      en: {
        label: 'English',
        direction: 'ltr',
        htmlLang: 'en',
      },
    },
  },

  markdown: {
    mermaid: true,
    hooks: {
      onBrokenMarkdownLinks: 'warn',
    },
  },

  presets: [
    [
      'classic',
      {
        docs: {
          path: 'docs',
          routeBasePath: 'docs',
          sidebarPath: './sidebars.ts',
          // doc/ 是内容事实源，展示站不提供编辑入口
        },
        blog: false,
        theme: {
          customCss: './src/css/custom.css',
        },
      } satisfies Preset.Options,
    ],
  ],

  themes: ['@docusaurus/theme-mermaid'],

  themeConfig: {
    image: 'img/architecture.png',
    colorMode: {
      defaultMode: 'dark',
      disableSwitch: false,
      respectPrefersColorScheme: true,
    },
    // Mermaid 跟随站点深/浅主题（复盘文档中的架构图与序列图）
    mermaid: {
      theme: { light: 'default', dark: 'dark' },
    },
    navbar: {
      title: 'FlowHub',
      logo: {
        alt: 'FlowHub Logo',
        src: 'img/logo.svg',
        width: 34,
        height: 34,
      },
      items: [
        { to: '/', label: '首页', position: 'left', exact: true },
        { to: '/#journey', label: '执行旅程', position: 'left', activeBaseRegex: '(?!)' },
        { to: '/#reliability', label: '可靠性', position: 'left', activeBaseRegex: '(?!)' },
        { to: '/#benchmark', label: '基准', position: 'left', activeBaseRegex: '(?!)' },
        { to: '/docs', label: '文档', position: 'left' },
        {
          href: 'https://github.com/wychmod/FlowHub',
          label: 'GitHub',
          position: 'right',
        },
        { type: 'localeDropdown', position: 'right' },
      ],
    },
    footer: {
      style: 'dark',
      links: [
        {
          title: '文档',
          items: [
            { label: '复盘总览', to: '/docs' },
            { label: '导出任务创建与幂等', to: '/docs/导出任务创建与幂等' },
            { label: '可靠投递（Outbox 与消费）', to: '/docs/可靠投递-Outbox与消费' },
            { label: '订单 Excel 导入', to: '/docs/订单Excel导入' },
          ],
        },
        {
          title: '项目',
          items: [
            { label: 'GitHub 仓库', href: 'https://github.com/wychmod/FlowHub' },
            { label: 'README 与快速开始', href: 'https://github.com/wychmod/FlowHub#readme' },
            { label: '架构图（SVG）', href: 'https://github.com/wychmod/FlowHub/blob/master/docs/images/architecture.svg' },
          ],
        },
        {
          title: '参考',
          items: [
            { label: 'AGENTS.md 工程规范', href: 'https://github.com/wychmod/FlowHub/blob/master/AGENTS.md' },
            { label: '演进路线', href: 'https://github.com/wychmod/FlowHub#演进路线' },
          ],
        },
      ],
      copyright: `FlowHub — 把「点击导出按钮之后发生的事」讲透的工程演示。`,
    },
    prism: {
      theme: prismThemes.oneDark,
      additionalLanguages: ['java', 'sql', 'bash', 'json'],
    },
  } satisfies Preset.ThemeConfig,
};

export default config;
