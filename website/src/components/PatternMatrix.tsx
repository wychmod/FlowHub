import Link from '@docusaurus/Link';
import useBrokenLinks from '@docusaurus/useBrokenLinks';
import { patterns, patternsHeading } from '../data/landing';
import { useLang } from './useLang';

/** 可靠性台账：按事故、工程防线和对应复盘展示八个模式。 */
export default function PatternMatrix() {
  useBrokenLinks().collectAnchor('reliability');
  const lang = useLang();
  const t = (value: { zh: string; en: string }) => value[lang];

  return (
    <section className="landingSection reliabilitySection" id="reliability">
      <div className="container">
        <header className="sectionMast sectionMast--light">
          <p className="sectionMast__index">02 / FAILURE LEDGER</p>
          <div>
            <h2>{t(patternsHeading.title)}</h2>
            <p>{t(patternsHeading.subtitle)}</p>
          </div>
        </header>

        <div className="incidentLedger" role="list">
          <div className="incidentLedger__head" aria-hidden="true">
            <span>NO.</span>
            <span>{lang === 'zh' ? '生产事故' : 'Failure'}</span>
            <span>{lang === 'zh' ? '工程防线' : 'Engineering defense'}</span>
            <span>{lang === 'zh' ? '机制' : 'Pattern'}</span>
          </div>

          {patterns.map((pattern) => (
            <article className="incidentRow" key={pattern.index} role="listitem">
              <span className="incidentRow__index">{String(pattern.index).padStart(2, '0')}</span>
              <h3>{t(pattern.scenario)}</h3>
              <p>{t(pattern.solution)}</p>
              <div className="incidentRow__action">
                <code>{t(pattern.tag)}</code>
                <Link aria-label={`${t(pattern.scenario)} · ${lang === 'zh' ? '深入复盘' : 'Deep dive'}`} to={pattern.docPath}>
                  <span aria-hidden="true">↗</span>
                </Link>
              </div>
            </article>
          ))}
        </div>
      </div>
    </section>
  );
}
