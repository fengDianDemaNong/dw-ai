-- 与 mysql/ 那份内容一致（那份同时给 H2 用，见 V23 顶部注释）。
--
-- 撤掉项目壳侧栏的「返回工作台」（V23 种子里 id = nav-proj-back 那一行）——
-- 左下角用户面板里已有同一个入口，侧栏再挂一条是重复的。
-- 为什么不改 V23、以及删完普通成员靠什么回工作台（面板判据一并放宽），见 mysql/ 那份的注释。
DELETE FROM nav_nodes WHERE id = 'nav-proj-back';
