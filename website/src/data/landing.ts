// 落地页全部文案与机制段代码片段（内容事实源：README.md / doc/README.md，不新造内容）。
// 语言键：zh 默认（zh-CN），en 供 English  locale 切换。

export type Lang = 'zh' | 'en';

export interface Localized {
  zh: string;
  en: string;
}

export const hero = {
  product: { zh: 'FlowHub', en: 'FlowHub' } as Localized,
  eyebrow: {
    zh: '可靠异步 Excel 流水线',
    en: 'Reliable asynchronous Excel pipeline',
  } as Localized,
  title: {
    zh: '让每一次批量数据流转，都有据可查。',
    en: 'Every bulk data job, traceable by design.',
  } as Localized,
  subtitle: {
    zh: '从任务受理、可靠投递、流式执行到原子发布，把一次导出交给一条可恢复、可观测、可追溯的生产链路。',
    en: 'From acceptance and reliable delivery to streamed execution and atomic publish, every export runs on a recoverable, observable, auditable production path.',
  } as Localized,
  ctaPrimary: { zh: '阅读系统复盘', en: 'Read the system review' } as Localized,
  ctaSecondary: { zh: '查看源码', en: 'View source' } as Localized,
  flowLabel: { zh: '任务状态模型', en: 'Job state model' } as Localized,
  sampleLabel: { zh: '演示链路，不代表性能实测', en: 'Flow demo, not a performance result' } as Localized,
};

export const techStack = [
  'Java 21',
  'Spring Boot 3.3',
  'MyBatis',
  'MySQL 8',
  'RabbitMQ',
  'Redis',
  'React',
  'SSE',
] as const;

export const heroSignals = [
  { value: '200,000', label: { zh: '确定性样本行', en: 'deterministic rows' } as Localized },
  { value: '09', label: { zh: '列互逆格式', en: 'reversible columns' } as Localized },
  { value: '08', label: { zh: '类事故防线', en: 'failure defenses' } as Localized },
  { value: 'DB', label: { zh: '唯一事实源', en: 'source of truth' } as Localized },
] as const;

export interface MechanismStep {
  id: string;
  accent: 'blue' | 'green' | 'amber';
  title: Localized;
  desc: Localized;
  codeLang: string;
  code: string;
  docPath: string;
  docLabel: Localized;
}

// 代码片段均摘自仓库真实源码（ExportJobMapper.xml / OutboxEventMapper.xml / ExportFileService.java / README curl 示例）
export const mechanismHeading = {
  title: { zh: '一次导出的完整旅程', en: 'The Complete Journey of One Export' } as Localized,
  subtitle: {
    zh: '六步交互时间线：点击每一步，看机制、真实代码与对应复盘文档。',
    en: 'An interactive six-step timeline: click each step for the mechanism, real code, and its deep-dive doc.',
  } as Localized,
  callout: {
    zh: 'MySQL 是唯一事实源，Redis 与 SSE 只是可失败的投影与通道。',
    en: 'MySQL is the single source of truth; Redis and SSE are merely fail-safe projections and channels.',
  } as Localized,
};

export const mechanismSteps: MechanismStep[] = [
  {
    id: 'create',
    accent: 'green',
    title: { zh: '创建受理', en: 'Accept & Create' },
    desc: {
      zh: '幂等键裁决「复用 / 冲突」，任务与「要执行它」的消息在同一个数据库事务里落库——事务性 Outbox，秒回 202，绝不出现「创建了却永远没人执行」。',
      en: 'An idempotency key arbitrates reuse vs. conflict; the job and the message to execute it land in the same DB transaction — the transactional Outbox. 202 accepted in seconds, never "created but never executed".',
    },
    codeLang: 'bash',
    code: `curl -X POST http://localhost:8080/api/v1/export-jobs \\
  -H "Content-Type: application/json" \\
  -H "Idempotency-Key: demo-0001" \\
  -d '{
    "selection": { "mode": "FILTER",
      "filter": { "order_status": ["PAID","SHIPPED"] } },
    "columns": ["order_no","customer_name","order_status",
                "total_amount","order_time"],
    "file_name": "已支付订单导出"
  }'
# → 202 Accepted  { job_id, job_no, status: "PENDING", total_rows }`,
    docPath: '/docs/导出任务创建与幂等',
    docLabel: { zh: '读复盘 03 · 导出任务创建与幂等', en: 'Doc 03 · Creation & Idempotency' },
  },
  {
    id: 'deliver',
    accent: 'green',
    title: { zh: '可靠投递', en: 'Reliable Delivery' },
    desc: {
      zh: '分发器定时扫描未发布事件，发送后等待 Publisher Confirm——仅 ACK 且无退回才回填 published_at（单向、不可逆），失败保留事件下轮补发：至少一次投递。',
      en: 'The dispatcher scans unpublished events and awaits the Publisher Confirm — published_at is stamped only on ACK with no return (one-way, irreversible). Failures keep the event for the next round: at-least-once delivery.',
    },
    codeLang: 'sql',
    code: `-- markPublished：IS NULL 单向条件保证不可逆，重放安全
UPDATE outbox_events
SET published_at = #{publishedAt}
WHERE id = #{id}
  AND published_at IS NULL;`,
    docPath: '/docs/可靠投递-Outbox与消费',
    docLabel: { zh: '读复盘 04 · 可靠投递', en: 'Doc 04 · Reliable Delivery' },
  },
  {
    id: 'claim',
    accent: 'green',
    title: { zh: '消费抢占', en: 'Claim by CAS' },
    desc: {
      zh: '手动 Ack 消费；消息可能重复投递、多实例可能同时消费——条件 UPDATE 抢到状态位才执行，影响 0 行直接 Ack 放弃。重复投递被收敛为最多一次有效执行，每次执行留 Attempt 审计行。',
      en: 'Manual-ack consumption; messages may be redelivered and instances race — only the conditional UPDATE that flips the status wins, 0 rows means ack-and-give-up. Redelivery converges to at-most-once effective execution, every attempt audited.',
    },
    codeLang: 'sql',
    code: `-- claimPending：数据库原子裁决执行权（1 抢到 / 0 放弃）
UPDATE export_jobs
SET status = 'RUNNING',
    attempt_count = attempt_count + 1,
    started_at = #{now},
    lease_expires_at = #{leaseExpiresAt},
    version = version + 1
WHERE id = #{jobId}
  AND status = 'PENDING'
  AND attempt_count < #{maxAttempts};`,
    docPath: '/docs/可靠投递-Outbox与消费',
    docLabel: { zh: '读复盘 04 · CAS 抢占与 Attempt 审计', en: 'Doc 04 · CAS & Attempt Audit' },
  },
  {
    id: 'execute',
    accent: 'blue',
    title: { zh: '流式执行', en: 'Streamed Execution' },
    desc: {
      zh: '创建时定格高水位 MAX(id)，执行时按任务快照重建筛选、Keyset 游标恒定成本批查——分页漂移、边导边增从设计上不存在；SXSSF 滑动窗口写盘，内存恒定。',
      en: 'The high-water mark MAX(id) is frozen at creation; execution rebuilds filters from the snapshot and pages with constant-cost Keyset seeks — no drift, no mid-export inserts. SXSSF sliding window keeps memory flat.',
    },
    codeLang: 'sql',
    code: `-- findBatch：Keyset 游标批查（与订单查询共享同一段动态 SQL 片段）
SELECT ... FROM orders
WHERE id > #{lastId}
  AND id <= #{maxOrderIdAtCreate}
  /* + 任务快照的筛选条件（criteriaConditions 共享片段） */
ORDER BY id ASC
LIMIT 1000;`,
    docPath: '/docs/导出执行与Excel生成',
    docLabel: { zh: '读复盘 05 · 导出执行与 Excel 生成', en: 'Doc 05 · Execution & Excel Generation' },
  },
  {
    id: 'progress',
    accent: 'blue',
    title: { zh: '进度推送', en: 'Progress & Push' },
    desc: {
      zh: '条件 UPDATE 推进事实源：RUNNING 单向、processed_rows 单调、租约续期，0 行即 fail-fast；Redis 投影尽力写入、失败降级；SSE 在事务提交后重读事实再广播——回滚永不广播，事件 id=jobId:version 天然免疫乱序。',
      en: 'A guarded conditional UPDATE moves the source of truth: RUNNING-only, monotone processed_rows, lease renewal, fail-fast on 0 rows. Redis projection is best-effort; SSE re-reads facts after commit — rollbacks never broadcast, jobId:version ids make reordering harmless.',
    },
    codeLang: 'sql',
    code: `-- updateProgress：status 单向 + 行数单调 + 租约续期
UPDATE export_jobs
SET processed_rows = #{processedRows},
    last_heartbeat_at = #{now},
    lease_expires_at = #{leaseExpiresAt},
    version = version + 1
WHERE id = #{jobId}
  AND status = 'RUNNING'
  AND processed_rows <= #{processedRows};`,
    docPath: '/docs/进度状态与SSE实时通知',
    docLabel: { zh: '读复盘 06 · 进度状态与 SSE', en: 'Doc 06 · Progress & SSE' },
  },
  {
    id: 'publish',
    accent: 'amber',
    title: { zh: '原子发布', en: 'Atomic Publish' },
    desc: {
      zh: 'Excel 先写成 .tmp 临时文件，全部落盘后经 ATOMIC_MOVE 瞬间切换为正式文件，再同事务 markSucceeded 登记路径与过期时间——数据库失败时补偿删除。不存在「下载到半个 Excel」。',
      en: 'The Excel is written to a .tmp file first; after the final flush, ATOMIC_MOVE swaps it into place atomically, then markSucceeded registers path and expiry in the same transaction — with compensating deletion on commit failure. No half-written downloads, ever.',
    },
    codeLang: 'java',
    code: `// publish：同文件系统 ATOMIC_MOVE，不支持时原样上抛、绝不降级为普通复制
Files.move(temporary, target,
    StandardCopyOption.ATOMIC_MOVE,
    StandardCopyOption.REPLACE_EXISTING);`,
    docPath: '/docs/导出执行与Excel生成',
    docLabel: { zh: '读复盘 05/07 · 发布协议与文件安全', en: 'Docs 05/07 · Publish Protocol & File Safety' },
  },
];

export const patternsHeading = {
  title: { zh: '8 个可靠性模式', en: '8 Reliability Patterns' } as Localized,
  subtitle: {
    zh: '每个「生产事故传闻」背后都有一个对应的工程解法——这是 FlowHub 最想传达的沉淀。',
    en: 'Behind every "production incident story" there is a corresponding engineering solution — this is what FlowHub most wants to convey.',
  } as Localized,
};

export interface ReliabilityPattern {
  index: number;
  tag: Localized;
  scenario: Localized;
  solution: Localized;
  docPath: string;
}

// 内容逐条取自 doc/README.md 第 7 节「事故剧本 → 解法」表
export const patterns: ReliabilityPattern[] = [
  {
    index: 1,
    tag: { zh: 'Idempotency-Key', en: 'Idempotency-Key' },
    scenario: { zh: '用户手抖点了两下导出', en: 'The user double-clicks export' },
    solution: {
      zh: '幂等键 + 规范化 request hash：相同请求复用任务，不同请求 409。',
      en: 'Idempotency key + normalized request hash: identical requests reuse the job; different ones get a 409.',
    },
    docPath: '/docs/导出任务创建与幂等',
  },
  {
    index: 2,
    tag: { zh: 'Outbox 模式', en: 'Outbox Pattern' },
    scenario: { zh: '库写成功、消息没发出去，任务永远没人执行', en: 'DB committed but the message never sent; the job is never executed' },
    solution: {
      zh: '事务性 Outbox：消息与业务同事务落库，分发器扫表补发，Confirm 后才打钩。',
      en: 'Transactional Outbox: message and business data commit together; the dispatcher re-sends and stamps only after confirm.',
    },
    docPath: '/docs/可靠投递-Outbox与消费',
  },
  {
    index: 3,
    tag: { zh: '抢单语义', en: 'Claim Semantics' },
    scenario: { zh: '消息重复投递 / 多实例同时消费', en: 'Redelivered messages / racing consumers' },
    solution: {
      zh: 'CAS 条件抢占 + Attempt 审计，重复投递收敛为最多一次有效执行。',
      en: 'CAS conditional claim + attempt audit: redelivery converges to at-most-once effective execution.',
    },
    docPath: '/docs/可靠投递-Outbox与消费',
  },
  {
    index: 4,
    tag: { zh: '流式处理', en: 'Streaming' },
    scenario: { zh: '50 万行导出把内存打爆', en: 'A 500k-row export blows up the heap' },
    solution: {
      zh: 'Keyset 游标批查 + SXSSF 滑动窗口，创建时定格高水位。',
      en: 'Keyset cursor batches + SXSSF sliding window, with the high-water mark frozen at creation.',
    },
    docPath: '/docs/导出执行与Excel生成',
  },
  {
    index: 5,
    tag: { zh: '原子发布', en: 'Atomic Publish' },
    scenario: { zh: '用户下载到写了一半的文件', en: 'A user downloads a half-written file' },
    solution: {
      zh: '.tmp → ATOMIC_MOVE 原子发布 → 数据库登记成功才算存在。',
      en: '.tmp → ATOMIC_MOVE atomic publish → the file exists only after DB registration.',
    },
    docPath: '/docs/导出执行与Excel生成',
  },
  {
    index: 6,
    tag: { zh: '租约机制', en: 'Lease Mechanism' },
    scenario: { zh: '服务重启后任务永远卡在 RUNNING', en: 'A job stuck in RUNNING forever after a restart' },
    solution: {
      zh: '心跳 + 租约：租约过期的 RUNNING 启动恢复时收敛为 FAILED，可人工重试。',
      en: 'Heartbeat + lease: expired-lease RUNNING jobs converge to FAILED on startup recovery, retriable by hand.',
    },
    docPath: '/docs/恢复与清理',
  },
  {
    index: 7,
    tag: { zh: '对账清理', en: 'Reconciliation' },
    scenario: { zh: '半成品 / 孤儿文件泄漏磁盘', en: 'Half-written / orphan files leaking disk space' },
    solution: {
      zh: '「删除成功才 EXPIRED」+ 孤儿三维对账（宽限 + 无活跃租约 + 无 DB 引用）。',
      en: '"Only count it EXPIRED after deletion" + three-way orphan reconciliation (grace period + no active lease + no DB reference).',
    },
    docPath: '/docs/恢复与清理',
  },
  {
    index: 8,
    tag: { zh: '路径防腐', en: 'Path Hardening' },
    scenario: { zh: '恶意文件名 / 路径穿越读走任意文件', en: 'Malicious filenames / path traversal reading arbitrary files' },
    solution: {
      zh: '受控根目录 + 路径双层防腐（文本 normalize + 物理符号链接验真）。',
      en: 'Controlled root + two-layer path hardening (text normalize + physical symlink verification).',
    },
    docPath: '/docs/文件安全与下载',
  },
];

export const benchmarkHeading = {
  title: { zh: '20 万行，等待受控实测', en: '200k Rows, Awaiting a Controlled Run' } as Localized,
  env: {
    zh: '受控采集：-Xmx512m · 确定性演示数据 · 环境与 commit 待性能脚本落地后标注',
    en: 'Controlled run: -Xmx512m · deterministic seed data · environment & commit to be annotated once the perf harness lands',
  } as Localized,
  pending: { zh: '待实测', en: 'Pending' } as Localized,
  demoNote: {
    zh: '下方进度条为演示动效（非实测数据）：SSE 刻度表示进度事件到达时刻',
    en: 'The progress bar below is a demo animation (not measured data): SSE ticks mark when progress events arrive.',
  } as Localized,
};

export const importStrip = {
  title: { zh: '导入，与导出完全对称', en: 'Import: Fully Symmetric to Export' } as Localized,
  desc: {
    zh: '独立表、独立队列拓扑，互不干扰地复用同一套可靠性骨架；并额外解决「有 300 行填错了怎么办」——PARTIAL 部分成功 + 行号对齐的错误报告。导入导出 9 列格式互为逆操作。',
    en: 'Separate tables and queue topologies reuse the same reliability skeleton without interference — and additionally answers "what if 300 rows are wrong": PARTIAL success with a row-aligned error report. Import/export share the same 9-column format, inverses of each other.',
  } as Localized,
  keywords: [
    { zh: '三层校验', en: 'Three-tier validation' },
    { zh: 'SAX 流式读取', en: 'SAX streaming read' },
    { zh: 'PARTIAL 部分成功', en: 'PARTIAL success' },
  ],
  docPath: '/docs/订单Excel导入',
  docLabel: { zh: '读复盘 09 · 订单 Excel 导入', en: 'Doc 09 · Order Excel Import' } as Localized,
};

export const GITHUB_REPO = 'https://github.com/wychmod/FlowHub';
