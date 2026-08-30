# 产品 0.1.0

状态：当前设计版本  
范围：平台原则、规范中心、建模中心

## 书面规范

1. [spec/00-platform.md](./spec/00-platform.md)
2. [spec/01-spec-center.md](./spec/01-spec-center.md)
3. [spec/02-modeling-center.md](./spec/02-modeling-center.md)

## 交互原型

完整可点击应用（登录 → 项目 → 规范 / 建模 / 其它模块）。与根目录实现是**快照副本**，独立存储。

```bash
# 在仓库根目录
npm run proto
```

浏览器：http://127.0.0.1:4173/  
顶栏有金色标签「产品原型 0.1.0」。

本机存储键：`dw-ai.proto.0.1.0`（不会覆盖实现里的 `dw-ai.state.v1`）。
