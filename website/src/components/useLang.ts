import useDocusaurusContext from '@docusaurus/useDocusaurusContext';

/** 当前站点语言：zh-CN 视为 zh，其余（en 等）视为 en。 */
export function useLang(): 'zh' | 'en' {
  const { i18n } = useDocusaurusContext();
  return i18n.currentLocale === 'zh-CN' ? 'zh' : 'en';
}
