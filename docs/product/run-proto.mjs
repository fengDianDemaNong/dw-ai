import { spawn } from 'node:child_process';
import { existsSync, readFileSync } from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const ver = process.argv[2] || '0.2.0';
const here = path.dirname(fileURLToPath(import.meta.url));
const vite = path.resolve(here, '../../node_modules/.bin/vite');
const warehouse = path.join(here, 'versions', ver, 'prototype');
const org = path.join(here, 'versions', ver, 'org');

if (!existsSync(path.join(warehouse, 'vite.config.ts'))) {
  console.error(`没有产品原型 ${ver}，目录：${warehouse}`);
  process.exit(1);
}
if (!existsSync(vite)) {
  console.error('未找到 vite，请先在仓库根目录执行 npm install');
  process.exit(1);
}

function vitePort(dir) {
  const text = readFileSync(path.join(dir, 'vite.config.ts'), 'utf8');
  const m = text.match(/port:\s*(\d+)/);
  return m ? m[1] : '?';
}

function start(cwd, label) {
  const child = spawn(vite, ['--config', 'vite.config.ts'], {
    cwd,
    stdio: 'inherit',
    env: process.env,
  });
  child.on('exit', (code) => {
    if (code) console.error(`${label} 退出 ${code}`);
  });
  return child;
}

const kids = [];
if (existsSync(path.join(org, 'vite.config.ts'))) {
  console.log(`组织平台 ${ver} → http://127.0.0.1:${vitePort(org)}/`);
  kids.push(start(org, '组织平台'));
}
console.log(`仓建设 ${ver} → http://127.0.0.1:${vitePort(warehouse)}/`);
kids.push(start(warehouse, '仓建设'));

const lineage = path.resolve(here, '../../dw-lineage/ui');
if (ver >= '0.2.0' && existsSync(path.join(lineage, 'vite.config.ts'))) {
  const lineageVite = existsSync(path.join(lineage, 'node_modules/.bin/vite'))
    ? path.join(lineage, 'node_modules/.bin/vite')
    : vite;
  console.log('数据地图 dw-lineage → http://127.0.0.1:5175/');
  const child = spawn(lineageVite, ['--config', 'vite.config.ts', '--port', '5175'], {
    cwd: lineage,
    stdio: 'inherit',
    env: { ...process.env, VITE_RUN_MODE: 'standalone', VITE_DEV_PORT: '5175' },
  });
  child.on('exit', (code) => {
    if (code) console.error(`数据地图 退出 ${code}`);
  });
  kids.push(child);
} else if (ver >= '0.2.0') {
  console.warn(`未找到 dw-lineage，数据地图 iframe 将空白。期望目录：${lineage}`);
}

for (const child of kids) {
  child.on('exit', (code) => {
    if (code) process.exit(code ?? 1);
  });
}
