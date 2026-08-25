#!/usr/bin/env node
/**
 * Claude Code PostToolUse hook：当前端文件被写入/编辑后，自动运行单测与构建并校验结果。
 *
 * 输入：stdin 上的 hook JSON（含 tool_input.file_path）。
 * 行为：仅当改动落在 frontend 目录下才触发；任一步失败则以非零退出码返回，
 *       在 transcript 中标记失败。其余模块的编辑不阻塞、不执行。
 */
'use strict';
const { execSync } = require('child_process');

const FRONTEND_DIR = 'D:/idea/export-flow/frontend';

/** 读取 stdin 全部内容。 */
function readStdin() {
  return new Promise((resolve) => {
    let data = '';
    process.stdin.setEncoding('utf8');
    process.stdin.on('data', (chunk) => (data += chunk));
    process.stdin.on('end', () => resolve(data));
  });
}

(async () => {
  const input = await readStdin();
  let filePath = '';
  try {
    filePath = (JSON.parse(input).tool_input || {}).file_path || '';
  } catch {
    // 无法解析（非期望事件）时直接忽略
  }

  // 仅当前端文件被改动才校验；否则跳过
  if (!filePath.includes('frontend')) {
    return;
  }

  console.log(`[frontend-hook] 检测到前端改动：${filePath}`);
  console.log('[frontend-hook] 运行 npm test 与 npm run build ...');
  // shell:true 让 npm 在 Windows 下通过 cmd.exe 正确解析 npm.cmd 与 && 连接
  execSync('npm test && npm run build', {
    cwd: FRONTEND_DIR,
    stdio: 'inherit',
    shell: true,
  });
  console.log('[frontend-hook] 单测与构建全部通过 ✅');
})().catch((err) => {
  // npm test 或 npm run build 任一步败（非零）时进入此处，抛错以标记 hook 失败
  console.error('[frontend-hook] 校验失败：', err.message);
  process.exit(1);
});