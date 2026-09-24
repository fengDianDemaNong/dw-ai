DROP DATABASE IF EXISTS `sql_tools`;
CREATE DATABASE  IF NOT EXISTS  `sql_tools` DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci;
SET NAMES utf8mb4;

USE `sql_tools`;
--
-- Table structure for table `col_rel`
--
DROP TABLE IF EXISTS `col_rel`;
CREATE TABLE `col_rel` (
                           `id` int(11) NOT NULL AUTO_INCREMENT,
                           `col_id` int(11) NOT NULL COMMENT 'column id',
                           `up_col_id` int(11) NOT NULL COMMENT 'upstream column id',
                           `del_flag` tinyint(1) NOT NULL DEFAULT '0' COMMENT '删除标志(0-有效,2-删除)',
                           `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
                           `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
                           PRIMARY KEY (`id`) USING BTREE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 ROW_FORMAT=DYNAMIC COMMENT='字段依赖';


--
-- Table structure for table `columns_v2`
--
DROP TABLE IF EXISTS `columns_v2`;
CREATE TABLE `columns_v2` (
                              `col_id` bigint(20) NOT NULL AUTO_INCREMENT COMMENT '字段id',
                              `tbl_id` bigint(20) NOT NULL COMMENT '表id',
                              `comment` varchar(6000) DEFAULT NULL COMMENT '字段中文名',
                              `column_name` varchar(256) NOT NULL COMMENT '字段名',
                              `type_name` varchar(200) DEFAULT NULL COMMENT '字段数据类型',
                              `col_classify` tinyint(1) NOT NULL DEFAULT '0' COMMENT '字段分类：0-> 非分区字段，1->分区字段',
                              `integer_idx` int(11) NOT NULL COMMENT '字段表内顺序',
                              `del_flag` tinyint(1) NOT NULL DEFAULT '0' COMMENT '删除标志(0-有效,2-删除)',
                              `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
                              `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
                              PRIMARY KEY (`col_id`) USING BTREE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 ROW_FORMAT=DYNAMIC COMMENT='字段表';


--
-- Table structure for table `database_params`
--
DROP TABLE IF EXISTS `database_params`;
CREATE TABLE `database_params` (
                                   `db_param_id` bigint(20) NOT NULL AUTO_INCREMENT,
                                   `db_id` bigint(20) NOT NULL COMMENT '数据库 id',
                                   `param_key` varchar(180) NOT NULL COMMENT '参数 key',
                                   `param_value` varchar(4000) DEFAULT NULL COMMENT '参数 val',
                                   `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
                                   `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
                                   PRIMARY KEY (`db_param_id`) USING BTREE,
                                   UNIQUE KEY `db_id` (`db_id`,`param_key`) USING BTREE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 ROW_FORMAT=DYNAMIC COMMENT='数据库配置';

--
-- Table structure for table `dbs`
--
DROP TABLE IF EXISTS `dbs`;
CREATE TABLE `dbs` (
                       `db_id` bigint(20) NOT NULL AUTO_INCREMENT,
                       `desc` varchar(4000) DEFAULT NULL COMMENT '说明',
                       `name` varchar(128) DEFAULT NULL,
                       `owner_name` varchar(128) DEFAULT NULL,
                       `owner_type` varchar(10) DEFAULT NULL,
                       `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
                       `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
                       PRIMARY KEY (`db_id`) USING BTREE,
                       UNIQUE KEY `unique_database` (`name`) USING BTREE
) ENGINE=InnoDB  DEFAULT CHARSET=utf8mb4 ROW_FORMAT=DYNAMIC COMMENT='数据库';

--
-- Dumping data for table `dbs`
--
LOCK TABLES `dbs` WRITE;
INSERT INTO `dbs` VALUES (1,'','dwd','','','2023-06-16 16:38:31','2023-06-16 16:38:31'),(2,'','output','','','2023-06-16 16:38:51','2023-06-16 16:38:51'),(3,'','ads','','','2023-06-16 16:38:53','2023-06-16 16:38:53'),(4,'','dws','','','2023-06-16 16:39:07','2023-06-16 16:39:07'),(5,'','stg','','','2023-06-16 16:39:13','2023-06-16 16:39:13'),(6,'','dm','','','2023-06-16 16:39:49','2023-06-16 16:39:49'),(8,'','ods','','','2023-06-16 16:39:57','2023-06-16 16:39:57');
UNLOCK TABLES;

--
-- Table structure for table `prd_mdm_result`
--
DROP TABLE IF EXISTS `prd_mdm_result`;
CREATE TABLE `prd_mdm_result` (
                                  `db_name` varchar(100) DEFAULT NULL,
                                  `tbl_name` varchar(100) DEFAULT NULL,
                                  `column_name` varchar(100) DEFAULT NULL,
                                  `type_name` varchar(100) DEFAULT NULL,
                                  `integer_idx` int(11) DEFAULT NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 ROW_FORMAT=DYNAMIC;


--
-- Table structure for table `table_params`
--
DROP TABLE IF EXISTS `table_params`;
CREATE TABLE `table_params` (
                                `tbl_param_id` bigint(20) NOT NULL AUTO_INCREMENT,
                                `tbl_id` bigint(20) NOT NULL,
                                `param_key` varchar(256) NOT NULL,
                                `param_value` varchar(4000) DEFAULT NULL,
                                `del_flag` tinyint(1) NOT NULL DEFAULT '0' COMMENT '删除标志(0-有效,2-删除)',
                                `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
                                `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
                                PRIMARY KEY (`tbl_param_id`) USING BTREE
) ENGINE=InnoDB  DEFAULT CHARSET=utf8mb4 ROW_FORMAT=DYNAMIC COMMENT='表配置';



--
-- Table structure for table `tbl_rel`
--
DROP TABLE IF EXISTS `tbl_rel`;
CREATE TABLE `tbl_rel` (
                           `id` int(11) NOT NULL AUTO_INCREMENT,
                           `tbl_id` int(11) NOT NULL COMMENT ' table_id',
                           `up_tbl_id` int(11) NOT NULL COMMENT 'upstream table_id',
                           `del_flag` tinyint(1) NOT NULL DEFAULT '0' COMMENT '删除标志(0-有效,2-删除)',
                           `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
                           `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
                           PRIMARY KEY (`id`) USING BTREE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 ROW_FORMAT=DYNAMIC COMMENT='表依赖';



--
-- Table structure for table `tbls`
--
DROP TABLE IF EXISTS `tbls`;
CREATE TABLE `tbls` (
                        `tbl_id` bigint(20) NOT NULL AUTO_INCREMENT,
                        `db_id` bigint(20) NOT NULL COMMENT '数据库id',
                        `db_name` varchar(256) DEFAULT NULL,
                        `tbl_name` varchar(256) DEFAULT NULL,
                        `tbl_type` varchar(128) DEFAULT NULL COMMENT '快照表:snapshot 拉链表:scd',
                        `owner` varchar(767) DEFAULT NULL,
                        `owner_type` varchar(10) DEFAULT NULL,
                        `del_flag` tinyint(1) NOT NULL DEFAULT '0' COMMENT '删除标志(0-有效,2-删除)',
                        `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
                        `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
                        PRIMARY KEY (`tbl_id`) USING BTREE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 ROW_FORMAT=DYNAMIC COMMENT='table 列表';




