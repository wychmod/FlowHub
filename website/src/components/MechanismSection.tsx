import { type KeyboardEvent, useRef, useState } from 'react';
import Link from '@docusaurus/Link';
import useBrokenLinks from '@docusaurus/useBrokenLinks';
import CodeBlock from '@theme/CodeBlock';
import { mechanismHeading, mechanismSteps } from '../data/landing';
import { useLang } from './useLang';

const INVARIANTS = [
  {
    code: 'TX',
    zh: '同事务双写',
    en: 'One transaction',
    zhDesc: '任务与消息一起落库',
    enDesc: 'Job and message commit together',
  },
  {
    code: 'CAS',
    zh: '原子抢占',
    en: 'Atomic claim',
    zhDesc: '重复投递只产生一次有效执行',
    enDesc: 'Redelivery yields one effective run',
  },
  {
    code: 'MOVE',
    zh: '原子发布',
    en: 'Atomic publish',
    zhDesc: '永远不会下载到半个文件',
    enDesc: 'Never expose a half-written file',
  },
] as const;

/** 六步执行工作台：状态说明、真实代码和复盘入口同步切换。 */
export default function MechanismSection() {
  useBrokenLinks().collectAnchor('journey');
  const lang = useLang();
  const t = (value: { zh: string; en: string }) => value[lang];
  const [activeId, setActiveId] = useState(mechanismSteps[0].id);
  const tabRefs = useRef<Array<HTMLButtonElement | null>>([]);
  const activeIndex = mechanismSteps.findIndex((step) => step.id === activeId);
  const active = mechanismSteps[activeIndex] ?? mechanismSteps[0];

  /** 保持 Tab 键盘语义完整，并同步移动焦点。 */
  const handleKeyDown = (event: KeyboardEvent<HTMLButtonElement>, index: number) => {
    let nextIndex: number | null = null;
    if (event.key === 'ArrowRight' || event.key === 'ArrowDown') {
      nextIndex = (index + 1) % mechanismSteps.length;
    } else if (event.key === 'ArrowLeft' || event.key === 'ArrowUp') {
      nextIndex = (index - 1 + mechanismSteps.length) % mechanismSteps.length;
    } else if (event.key === 'Home') {
      nextIndex = 0;
    } else if (event.key === 'End') {
      nextIndex = mechanismSteps.length - 1;
    }

    if (nextIndex === null) return;
    event.preventDefault();
    setActiveId(mechanismSteps[nextIndex].id);
    tabRefs.current[nextIndex]?.focus();
  };

  const codeSource =
    active.codeLang === 'sql'
      ? 'persistence / guarded update'
      : active.codeLang === 'java'
        ? 'file system / publish'
        : 'http / create job';

  return (
    <section className="landingSection journeySection" id="journey">
      <div className="container">
        <header className="sectionMast">
          <p className="sectionMast__index">01 / EXECUTION PATH</p>
          <div>
            <h2>{t(mechanismHeading.title)}</h2>
            <p>{t(mechanismHeading.subtitle)}</p>
          </div>
        </header>

        <div className="journeyWorkbench">
          <div className="journeyWorkbench__bar" aria-hidden="true">
            <span>FLOWHUB / EXPORT-JOB</span>
            <span>TRACE ENABLED</span>
          </div>

          <div className="journeyTabs" role="tablist" aria-label={t(mechanismHeading.title)}>
            {mechanismSteps.map((step, index) => {
              const selected = step.id === active.id;
              return (
                <button
                  aria-controls="journey-panel"
                  aria-selected={selected}
                  className={`journeyTab journeyTab--${step.accent}${selected ? ' journeyTab--active' : ''}`}
                  id={`journey-tab-${step.id}`}
                  key={step.id}
                  onClick={() => setActiveId(step.id)}
                  onKeyDown={(event) => handleKeyDown(event, index)}
                  ref={(node) => {
                    tabRefs.current[index] = node;
                  }}
                  role="tab"
                  tabIndex={selected ? 0 : -1}
                  type="button"
                >
                  <span>{String(index + 1).padStart(2, '0')}</span>
                  <strong>{t(step.title)}</strong>
                </button>
              );
            })}
          </div>

          <div
            aria-labelledby={`journey-tab-${active.id}`}
            className="journeyPanel"
            id="journey-panel"
            role="tabpanel"
          >
            <div className="journeyNarrative">
              <div className="journeyNarrative__step">
                <span>{String(activeIndex + 1).padStart(2, '0')}</span>
                <em>{active.id.toUpperCase()}</em>
              </div>
              <h3>{t(active.title)}</h3>
              <p>{t(active.desc)}</p>
              <Link className="textLink" to={active.docPath}>
                {t(active.docLabel)} <span aria-hidden="true">→</span>
              </Link>
            </div>

            <div className="journeyCode">
              <div className="journeyCode__meta">
                <span>{codeSource}</span>
                <span>{active.codeLang.toUpperCase()}</span>
              </div>
              <CodeBlock language={active.codeLang}>{active.code}</CodeBlock>
            </div>
          </div>
        </div>

        <div className="invariantRail">
          {INVARIANTS.map((item) => (
            <div className="invariant" key={item.code}>
              <span>{item.code}</span>
              <div>
                <strong>{lang === 'zh' ? item.zh : item.en}</strong>
                <small>{lang === 'zh' ? item.zhDesc : item.enDesc}</small>
              </div>
            </div>
          ))}
        </div>

        <blockquote className="truthStatement">
          <span>TRUTH SOURCE</span>
          <p>{t(mechanismHeading.callout)}</p>
        </blockquote>
      </div>
    </section>
  );
}
