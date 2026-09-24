/**
 * @antv/g6-pc@0.8.18 静态 import 了 "@antv/algorithm/lib/asyncIndex"，
 * 但该文件在 @antv/algorithm 的所有已发布版本中都不存在（上游打包缺陷），
 * 导致 vite 构建报 "Rollup failed to resolve import"。
 *
 * g6-pc 的用法是 `Algorithm = { ...AlgorithmSync, ...AlgorithmAsync }`，
 * 因此用一个空模块顶替即可：Algorithm 仍然包含全部同步算法，
 * 只是缺少异步变体。本项目未使用 G6 的 Algorithm 能力。
 *
 * 上游修复后可删除本文件及 vite.config.ts 中对应的 alias。
 */
export {};
