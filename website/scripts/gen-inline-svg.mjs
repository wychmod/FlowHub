// 构建期先同步 docs/images 品牌资产，再生成架构图的 TS 内联模块。
import { copyFileSync, readFileSync, writeFileSync } from 'node:fs';
import { join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const here = resolve(fileURLToPath(new URL('.', import.meta.url)));
const sourceDir = join(here, '..', '..', 'docs', 'images');
const staticDir = join(here, '..', 'static', 'img');

for (const fileName of ['logo.svg', 'logo-full.svg', 'architecture.svg', 'architecture.png']) {
  copyFileSync(join(sourceDir, fileName), join(staticDir, fileName));
}

const svg = readFileSync(join(sourceDir, 'architecture.svg'), 'utf8');

// 剥离 XML 注释（注入 HTML 无需保留）；剔除外层 width/height，交给 CSS 控制自适应
const cleaned = svg
  .replace(/<!--[\s\S]*?-->/g, '')
  .replace(' width="1240" height="980"', '');

if (cleaned.includes('`') || cleaned.includes('${')) {
  throw new Error('[gen-inline-svg] SVG 内容含模板字符串特殊字符，需调整生成方式');
}

const out = `// 本文件由 scripts/gen-inline-svg.mjs 自动生成，请勿手改。源：docs/images/architecture.svg
export const architectureSvg = \`${cleaned}\`;
`;

writeFileSync(join(here, '..', 'src', 'components', 'architecture.inline.ts'), out, 'utf8');
console.log('[gen-inline-svg] 品牌资产已同步，architecture.inline.ts 生成完成');
