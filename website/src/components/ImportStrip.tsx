import Link from '@docusaurus/Link';
import { importStrip } from '../data/landing';
import { useLang } from './useLang';

const EXPORT_STEPS = [
  { zh: '快照筛选', en: 'Snapshot' },
  { zh: 'Keyset 批查', en: 'Keyset read' },
  { zh: 'SXSSF 写盘', en: 'SXSSF write' },
] as const;

const IMPORT_STEPS = [
  { zh: 'SAX 流读', en: 'SAX read' },
  { zh: '三层校验', en: '3-tier validation' },
  { zh: 'PARTIAL 收敛', en: 'PARTIAL result' },
] as const;

/** 导入与导出共享可靠性骨架，数据方向相反。 */
export default function ImportStrip() {
  const lang = useLang();
  const t = (value: { zh: string; en: string }) => value[lang];

  return (
    <section className="landingSection mirrorSection" id="import">
      <div className="container">
        <header className="sectionMast">
          <p className="sectionMast__index">04 / MIRRORED PIPELINE</p>
          <div>
            <h2>{t(importStrip.title)}</h2>
            <p>{t(importStrip.desc)}</p>
          </div>
        </header>

        <div className="mirrorDiagram">
          <div className="mirrorLane mirrorLane--export">
            <div className="mirrorLane__head">
              <span>OUTBOUND</span>
              <strong>{lang === 'zh' ? '导出' : 'Export'}</strong>
            </div>
            <ol>
              {EXPORT_STEPS.map((step, index) => (
                <li key={step.en}>
                  <span>{String(index + 1).padStart(2, '0')}</span>
                  <strong>{lang === 'zh' ? step.zh : step.en}</strong>
                </li>
              ))}
            </ol>
          </div>

          <div className="mirrorWorkbook" aria-label={lang === 'zh' ? '九列 Excel 互逆格式' : 'Reversible nine-column Excel format'}>
            <div className="mirrorWorkbook__file">
              <span>XLSX</span>
              <strong>09</strong>
              <small>{lang === 'zh' ? '列单一事实源' : 'column contract'}</small>
            </div>
            <div className="mirrorWorkbook__core">
              <span>OUTBOX</span>
              <span>CAS</span>
              <span>LEASE</span>
              <span>SSE</span>
            </div>
          </div>

          <div className="mirrorLane mirrorLane--import">
            <div className="mirrorLane__head">
              <span>INBOUND</span>
              <strong>{lang === 'zh' ? '导入' : 'Import'}</strong>
            </div>
            <ol>
              {IMPORT_STEPS.map((step, index) => (
                <li key={step.en}>
                  <span>{String(index + 1).padStart(2, '0')}</span>
                  <strong>{lang === 'zh' ? step.zh : step.en}</strong>
                </li>
              ))}
            </ol>
          </div>
        </div>

        <div className="mirrorFooter">
          <div className="mirrorKeywords">
            {importStrip.keywords.map((keyword) => (
              <span key={keyword.en}>{t(keyword)}</span>
            ))}
          </div>
          <Link className="textLink" to={importStrip.docPath}>
            {t(importStrip.docLabel)} <span aria-hidden="true">→</span>
          </Link>
        </div>

        <div className="finalCta">
          <div>
            <span>12 CHAPTERS / SOURCE-LED REVIEW</span>
            <h2>{lang === 'zh' ? '从一次点击，读到完整生产链路。' : 'From one click to the complete production path.'}</h2>
          </div>
          <Link className="flowButton flowButton--primary" to="/docs">
            {lang === 'zh' ? '开始阅读' : 'Start reading'} <span aria-hidden="true">→</span>
          </Link>
        </div>
      </div>
    </section>
  );
}
