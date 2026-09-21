// 构建期同步脚本：把仓库 doc/（事实源）拷贝为 website/docs/，并做最小适配：
// 1. README.md → index.md（slug=/docs 首页，sidebar_position=0，注入 EN 摘要，渐进双语的第一步）
// 2. 其余 01-12 篇按文件名数字前缀注入 sidebar_position，保证侧边栏顺序
// 3. 指向 ../README.md、../AGENTS.md 的相对链接改写为 GitHub 绝对地址（站外资源）
// 4. 历史遗留的 "doc/NN" 站内引用文本不动；Docs 插件会报告死链并按配置降级为警告
import { cpSync, existsSync, mkdirSync, readFileSync, readdirSync, rmSync, writeFileSync } from 'node:fs';
import { join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const here = resolve(fileURLToPath(new URL('.', import.meta.url)));
const websiteRoot = resolve(here, '..');
const sourceDir = resolve(websiteRoot, '..', 'doc');
const targetDir = join(websiteRoot, 'docs');

if (!existsSync(sourceDir)) {
  console.error(`[sync-docs] 未找到事实源目录：${sourceDir}`);
  process.exit(1);
}

rmSync(targetDir, { recursive: true, force: true });
mkdirSync(targetDir, { recursive: true });

const GITHUB_BASE = 'https://github.com/wychmod/FlowHub/blob/master';

function rewriteOutboundLinks(content) {
  return content
    .replaceAll('](../README.md)', `](${GITHUB_BASE}/README.md)`)
    .replaceAll('](../AGENTS.md)', `](${GITHUB_BASE}/AGENTS.md)`)
    .replaceAll('](README.md)', '](./index.md)');
}

const MAIN_DOC_EN_ABSTRACT = `> **English Abstract** — FlowHub is an enterprise-grade asynchronous Excel export & import center.
> This main document gives you the full picture: architecture, the complete journey of one export,
> the task state machine, and a map to all 12 deep-dive retrospectives. Start here.
>
> 中文正文见下。复盘文档的英文全文翻译为渐进式推进，当前先提供摘要。

---

`;

let synced = 0;
for (const file of readdirSync(sourceDir)) {
  if (!file.endsWith('.md')) continue;
  const raw = readFileSync(join(sourceDir, file), 'utf8');
  const content = rewriteOutboundLinks(raw);

  if (file === 'README.md') {
    const frontMatter = [
      '---',
      // slug 以 / 结尾落在 docs 插件根：路由即 /docs/（build/docs/index.html）
      'slug: /',
      'sidebar_position: 0',
      'title: 复盘总览（主文档）',
      '---',
      '',
      MAIN_DOC_EN_ABSTRACT,
    ].join('\n');
    writeFileSync(join(targetDir, 'index.md'), frontMatter + content, 'utf8');
  } else {
    const order = /^(\d+)/.exec(file)?.[1];
    const num = order ? Number.parseInt(order, 10) : 99;
    const frontMatter = `---\nsidebar_position: ${num}\n---\n\n`;
    writeFileSync(join(targetDir, file), frontMatter + content, 'utf8');
  }
  synced += 1;
}

console.log(`[sync-docs] 已从 doc/ 同步 ${synced} 篇复盘文档 → website/docs/`);
