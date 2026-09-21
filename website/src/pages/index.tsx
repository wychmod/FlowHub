import Layout from '@theme/Layout';
import HeroSection from '../components/HeroSection';
import MechanismSection from '../components/MechanismSection';
import PatternMatrix from '../components/PatternMatrix';
import BenchmarkSection from '../components/BenchmarkSection';
import ImportStrip from '../components/ImportStrip';
import { useLang } from '../components/useLang';

export default function Home() {
  const lang = useLang();

  return (
    <Layout
      title={lang === 'zh' ? '可靠异步 Excel 流水线' : 'Reliable asynchronous Excel pipeline'}
      description={
        lang === 'zh'
          ? '让每一次批量数据流转都有据可查：事务性 Outbox、CAS 抢占、流式执行、SSE 校准与原子文件发布。'
          : 'Trace every bulk data job through transactional Outbox, CAS claim, streamed execution, SSE calibration, and atomic file publish.'
      }
    >
      <main className="landingRoot">
        <HeroSection />
        <MechanismSection />
        <PatternMatrix />
        <BenchmarkSection />
        <ImportStrip />
      </main>
    </Layout>
  );
}
