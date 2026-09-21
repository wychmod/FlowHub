import Link from '@docusaurus/Link';
import { GITHUB_REPO, hero, heroSignals, techStack } from '../data/landing';
import { useLang } from './useLang';

const FLOW_STAGES = [
  { id: 'api', code: '202', zh: '创建受理', en: 'Accepted' },
  { id: 'outbox', code: 'TX', zh: '同事务落库', en: 'Committed' },
  { id: 'queue', code: 'ACK', zh: '可靠投递', en: 'Delivered' },
  { id: 'worker', code: 'CAS', zh: '消费抢占', en: 'Claimed' },
  { id: 'stream', code: '64K', zh: '流式执行', en: 'Streaming' },
  { id: 'file', code: 'XLSX', zh: '原子发布', en: 'Published' },
] as const;

/** 首屏以真实任务状态模型呈现 FlowHub 的可靠执行链路。 */
export default function HeroSection() {
  const lang = useLang();
  const t = (value: { zh: string; en: string }) => value[lang];

  return (
    <header className="flowHero">
      <div className="flowHero__rule flowHero__rule--left" aria-hidden="true" />
      <div className="flowHero__rule flowHero__rule--right" aria-hidden="true" />

      <div className="container flowHero__content">
        <div className="flowHero__statusLine">
          <span className="signalDot" aria-hidden="true" />
          <span>{t(hero.eyebrow)}</span>
          <span className="flowHero__statusMeta">BUILD / RECOVER / AUDIT</span>
        </div>

        <div className="flowHero__headline">
          <p className="flowHero__eyebrow">RELIABLE DATA PIPELINES</p>
          <h1>{t(hero.product)}</h1>
          <p className="flowHero__lead">{t(hero.title)}</p>
          <p className="flowHero__summary">{t(hero.subtitle)}</p>

          <div className="flowHero__actions">
            <Link className="flowButton flowButton--primary" to="/docs">
              {t(hero.ctaPrimary)} <span aria-hidden="true">→</span>
            </Link>
            <Link className="flowButton flowButton--secondary" to={GITHUB_REPO}>
              {t(hero.ctaSecondary)} <span aria-hidden="true">↗</span>
            </Link>
          </div>

        </div>

        <div className="flowModel" aria-label={t(hero.flowLabel)}>
          <div className="flowModel__header">
            <div>
              <span className="flowModel__label">{t(hero.flowLabel)}</span>
              <strong>EXP-20260921-0007</strong>
            </div>
            <div className="flowModel__state">
              <span className="signalDot signalDot--cyan" aria-hidden="true" />
              RUNNING
            </div>
          </div>

          <ol className="flowTrack">
            {FLOW_STAGES.map((stage, index) => (
              <li className={`flowNode flowNode--${stage.id}`} key={stage.id}>
                <span className="flowNode__index">{String(index + 1).padStart(2, '0')}</span>
                <span className="flowNode__point" aria-hidden="true">
                  <span className="flowNode__packet" />
                </span>
                <strong>{stage.code}</strong>
                <small>{lang === 'zh' ? stage.zh : stage.en}</small>
              </li>
            ))}
          </ol>

          <div className="flowProgress">
            <div className="flowProgress__copy">
              <span>processed_rows</span>
              <strong>148,000 / 200,000</strong>
            </div>
            <div className="flowProgress__track" aria-hidden="true">
              <span />
            </div>
            <div className="flowProgress__foot">
              <span>job_version: 37</span>
              <span>lease: healthy</span>
              <span>{t(hero.sampleLabel)}</span>
            </div>
          </div>
        </div>
      </div>

      <div className="signalRail">
        <div className="container signalRail__inner">
          <div className="signalRail__metrics">
            {heroSignals.map((signal) => (
              <div className="signalMetric" key={signal.value}>
                <strong>{signal.value}</strong>
                <span>{t(signal.label)}</span>
              </div>
            ))}
          </div>
          <div className="techRail" aria-label={lang === 'zh' ? '技术栈' : 'Technology stack'}>
            {techStack.map((item) => (
              <span key={item}>{item}</span>
            ))}
          </div>
        </div>
      </div>
    </header>
  );
}
