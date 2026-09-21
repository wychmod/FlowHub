import { useEffect, useRef, useState } from 'react';
import Link from '@docusaurus/Link';
import useBrokenLinks from '@docusaurus/useBrokenLinks';
import { benchmarkHeading } from '../data/landing';
import { useLang } from './useLang';

const ROW_COUNT = 200_000;

/** 性能区只展示已知数据，未知指标保持明确的待实测状态。 */
export default function BenchmarkSection() {
  useBrokenLinks().collectAnchor('benchmark');
  const lang = useLang();
  const t = (value: { zh: string; en: string }) => value[lang];
  const sectionRef = useRef<HTMLElement>(null);
  const [inView, setInView] = useState(false);

  useEffect(() => {
    const element = sectionRef.current;
    if (!element || !('IntersectionObserver' in window)) {
      setInView(true);
      return;
    }
    const observer = new IntersectionObserver(
      ([entry]) => {
        if (entry.isIntersecting) setInView(true);
      },
      { threshold: 0.25 },
    );
    observer.observe(element);
    return () => observer.disconnect();
  }, []);

  const pendingMetrics = [
    { key: 'elapsed', zh: '创建至终态耗时', en: 'Create to terminal' },
    { key: 'rss', zh: '峰值 JVM RSS', en: 'Peak JVM RSS' },
    { key: 'size', zh: '产物文件大小', en: 'Output file size' },
  ];

  return (
    <section className="benchmarkSection" id="benchmark" ref={sectionRef}>
      <div className="container">
        <header className="benchmarkHeader">
          <div>
            <p className="sectionMast__index">03 / EVIDENCE FIRST</p>
            <h2>{t(benchmarkHeading.title)}</h2>
          </div>
          <p>{t(benchmarkHeading.env)}</p>
        </header>

        <div className="benchmarkBoard">
          <div className="benchmarkPrimary">
            <span>DATASET</span>
            <strong>{ROW_COUNT.toLocaleString(lang === 'zh' ? 'zh-CN' : 'en-US')}</strong>
            <p>{lang === 'zh' ? '行确定性演示订单' : 'deterministic demo orders'}</p>
          </div>

          <dl className="benchmarkPending">
            {pendingMetrics.map((metric) => (
              <div key={metric.key}>
                <dt>{lang === 'zh' ? metric.zh : metric.en}</dt>
                <dd>--</dd>
                <span>{t(benchmarkHeading.pending).toUpperCase()}</span>
              </div>
            ))}
          </dl>
        </div>

        <div className="benchmarkRunway">
          <div className="benchmarkRunway__meta">
            <span>SIMULATED PROGRESS</span>
            <strong>job.progress · 99%</strong>
          </div>
          <div className="benchmarkRunway__track" aria-hidden="true">
            <span className={inView ? 'benchmarkRunway__fill benchmarkRunway__fill--run' : 'benchmarkRunway__fill'} />
            <i style={{ left: '18%' }} />
            <i style={{ left: '46%' }} />
            <i style={{ left: '81%' }} />
          </div>
          <div className="benchmarkRunway__foot">
            <p>{t(benchmarkHeading.demoNote)}</p>
            <Link className="textLink textLink--dark" to="/docs/测试与质量守门">
              {lang === 'zh' ? '阅读验证契约' : 'Read the verification contract'} <span aria-hidden="true">→</span>
            </Link>
          </div>
        </div>
      </div>
    </section>
  );
}
