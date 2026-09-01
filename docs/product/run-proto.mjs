import { spawn } from 'node:child_process';
import { existsSync } from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const ver = process.argv[2] || '0.1.4';
const here = path.dirname(fileURLToPath(import.meta.url));
const proto = path.join(here, 'versions', ver, 'prototype');
const vite = path.resolve(here, '../../node_modules/.bin/vite');

if (!existsSync(path.join(proto, 'vite.config.ts'))) {
  console.error(`没有产品原型 ${ver}，目录：${proto}`);
  process.exit(1);
}
if (!existsSync(vite)) {
  console.error('未找到 vite，请先在仓库根目录执行 npm install');
  process.exit(1);
}

const child = spawn(vite, ['--config', 'vite.config.ts'], {
  cwd: proto,
  stdio: 'inherit',
  env: process.env,
});
child.on('exit', (code) => process.exit(code ?? 1));
