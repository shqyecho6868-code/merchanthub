-- Source: doc/db/tables_xxl_job.sql from the official xxl-job repo at tag 3.4.2 (matches xuxueli/xxl-job-admin:3.4.2 in docker-compose); only comments and sample text translated to English
#
# XXL-JOB
# Copyright (c) 2015-present, xuxueli.

CREATE database if NOT EXISTS `xxl_job` default character set utf8mb4 collate utf8mb4_unicode_ci;
use `xxl_job`;

SET NAMES utf8mb4;

## —————————————————————— job group and registry ——————————————————

CREATE TABLE `xxl_job_group`
(
    `id`           int(11)     NOT NULL AUTO_INCREMENT,
    `app_name`     varchar(64) NOT NULL COMMENT 'Executor AppName',
    `title`        varchar(64) NOT NULL COMMENT 'Executor name',
    `address_type` tinyint(4)  NOT NULL DEFAULT '0' COMMENT 'Executor address type: 0=auto-registered, 1=entered manually',
    `address_list` text COMMENT 'Executor address list, comma-separated',
    `update_time`  datetime             DEFAULT NULL,
    PRIMARY KEY (`id`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4;

CREATE TABLE `xxl_job_registry`
(
    `id`                bigint(20)   NOT NULL AUTO_INCREMENT,
    `registry_group`    varchar(50)  NOT NULL,
    `registry_key`      varchar(255) NOT NULL,
    `registry_value`    varchar(255) NOT NULL,
    `update_time`       datetime DEFAULT NULL,
    PRIMARY KEY (`id`),
    UNIQUE KEY `i_g_k_v` (`registry_group`, `registry_key`, `registry_value`) USING BTREE
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4;

## —————————————————————— job info ——————————————————

CREATE TABLE `xxl_job_info`
(
    `id`                        int(11)      NOT NULL AUTO_INCREMENT,
    `job_group`                 int(11)      NOT NULL COMMENT 'Executor primary key ID',
    `job_desc`                  varchar(255) NOT NULL,
    `add_time`                  datetime              DEFAULT NULL,
    `update_time`               datetime              DEFAULT NULL,
    `author`                    varchar(64)           DEFAULT NULL COMMENT 'Author',
    `alarm_email`               varchar(255)          DEFAULT NULL COMMENT 'Alarm email',
    `schedule_type`             varchar(50)  NOT NULL DEFAULT 'NONE' COMMENT 'Schedule type',
    `schedule_conf`             varchar(128)          DEFAULT NULL COMMENT 'Schedule config; meaning depends on the schedule type',
    `misfire_strategy`          varchar(50)  NOT NULL DEFAULT 'DO_NOTHING' COMMENT 'Misfire strategy',
    `executor_route_strategy`   varchar(50)           DEFAULT NULL COMMENT 'Executor routing strategy',
    `executor_handler`          varchar(255)          DEFAULT NULL COMMENT 'Job handler',
    `executor_param`            text                  DEFAULT NULL COMMENT 'Job parameters',
    `executor_block_strategy`   varchar(50)           DEFAULT NULL COMMENT 'Blocking strategy',
    `executor_timeout`          int(11)      NOT NULL DEFAULT '0' COMMENT 'Job execution timeout, in seconds',
    `executor_fail_retry_count` int(11)      NOT NULL DEFAULT '0' COMMENT 'Retry count on failure',
    `glue_type`                 varchar(50)  NOT NULL COMMENT 'GLUE type',
    `glue_source`               mediumtext COMMENT 'GLUE source code',
    `glue_remark`               varchar(128)          DEFAULT NULL COMMENT 'GLUE remark',
    `glue_updatetime`           datetime              DEFAULT NULL COMMENT 'GLUE update time',
    `child_jobid`               varchar(255)          DEFAULT NULL COMMENT 'Child job IDs, comma-separated',
    `trigger_status`            tinyint(4)   NOT NULL DEFAULT '0' COMMENT 'Trigger status: 0-stopped, 1-running',
    `trigger_last_time`         bigint(13)   NOT NULL DEFAULT '0' COMMENT 'Last trigger time',
    `trigger_next_time`         bigint(13)   NOT NULL DEFAULT '0' COMMENT 'Next trigger time',
    PRIMARY KEY (`id`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4;

CREATE TABLE `xxl_job_logglue`
(
    `id`          int(11)      NOT NULL AUTO_INCREMENT,
    `job_id`      int(11)      NOT NULL COMMENT 'Job primary key ID',
    `glue_type`   varchar(50) DEFAULT NULL COMMENT 'GLUE type',
    `glue_source` mediumtext COMMENT 'GLUE source code',
    `glue_remark` varchar(128) NOT NULL COMMENT 'GLUE remark',
    `add_time`    datetime    DEFAULT NULL,
    `update_time` datetime    DEFAULT NULL,
    PRIMARY KEY (`id`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4;

## —————————————————————— job log and report ——————————————————

CREATE TABLE `xxl_job_log`
(
    `id`                        bigint(20)          NOT NULL AUTO_INCREMENT,
    `job_group`                 int(11)             NOT NULL COMMENT 'Executor primary key ID',
    `job_id`                    int(11)             NOT NULL COMMENT 'Job primary key ID',
    `executor_address`          varchar(255)        DEFAULT NULL COMMENT 'Executor address used for this run',
    `executor_handler`          varchar(255)        DEFAULT NULL COMMENT 'Job handler',
    `executor_param`            text                DEFAULT NULL COMMENT 'Job parameters',
    `executor_sharding_param`   varchar(20)         DEFAULT NULL COMMENT 'Job sharding parameter, e.g. 1/2',
    `executor_fail_retry_count` int(11)             NOT NULL DEFAULT '0' COMMENT 'Retry count on failure',
    `trigger_time`              datetime            DEFAULT NULL COMMENT 'Trigger time',
    `trigger_code`              int(11)             NOT NULL COMMENT 'Trigger result',
    `trigger_msg`               text                COMMENT 'Trigger log',
    `handle_time`               datetime            DEFAULT NULL COMMENT 'Handle time',
    `handle_code`               int(11)             NOT NULL COMMENT 'Handle status',
    `handle_msg`                text                COMMENT 'Handle log',
    `alarm_status`              tinyint(4)          NOT NULL DEFAULT '0' COMMENT 'Alarm status: 0-default, 1-no alarm needed, 2-alarm sent, 3-alarm failed',
    PRIMARY KEY (`id`),
    KEY `I_trigger_time` (`trigger_time`),
    KEY `I_handle_code` (`handle_code`),
    KEY `I_jobgroup` (`job_group`),
    KEY `I_jobid` (`job_id`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4;

CREATE TABLE `xxl_job_log_report`
(
    `id`            int(11) NOT NULL AUTO_INCREMENT,
    `trigger_day`   datetime         DEFAULT NULL COMMENT 'Trigger time',
    `running_count` int(11) NOT NULL DEFAULT '0' COMMENT 'Running log count',
    `suc_count`     int(11) NOT NULL DEFAULT '0' COMMENT 'Succeeded log count',
    `fail_count`    int(11) NOT NULL DEFAULT '0' COMMENT 'Failed log count',
    `update_time`   datetime         DEFAULT NULL,
    PRIMARY KEY (`id`),
    UNIQUE KEY `i_trigger_day` (`trigger_day`) USING BTREE
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4;

## —————————————————————— lock ——————————————————

CREATE TABLE `xxl_job_lock`
(
    `lock_name` varchar(50) NOT NULL COMMENT 'Lock name',
    PRIMARY KEY (`lock_name`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4;

## —————————————————————— user ——————————————————

CREATE TABLE `xxl_job_user`
(
    `id`         int(11)     NOT NULL AUTO_INCREMENT,
    `username`   varchar(50) NOT NULL COMMENT 'Username',
    `password`   varchar(100) NOT NULL COMMENT 'Encrypted password',
    `token`      varchar(100) DEFAULT NULL COMMENT 'Login token',
    `role`       tinyint(4)  NOT NULL COMMENT 'Role: 0-normal user, 1-admin',
    `permission` varchar(255) DEFAULT NULL COMMENT 'Permission: executor ID list, comma-separated',
    PRIMARY KEY (`id`),
    UNIQUE KEY `i_username` (`username`) USING BTREE
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4;


## —————————————————————— for default data ——————————————————

INSERT INTO `xxl_job_group`(`id`, `app_name`, `title`, `address_type`, `address_list`, `update_time`)
    VALUES (1, 'xxl-job-executor-sample', 'Generic executor sample', 0, NULL, now()),
           (2, 'xxl-job-executor-sample-ai', 'AI executor sample', 0, NULL, now());

INSERT INTO `xxl_job_info`(`id`, `job_group`, `job_desc`, `add_time`, `update_time`, `author`, `alarm_email`,
                           `schedule_type`, `schedule_conf`, `misfire_strategy`, `executor_route_strategy`,
                           `executor_handler`, `executor_param`, `executor_block_strategy`, `executor_timeout`,
                           `executor_fail_retry_count`, `glue_type`, `glue_source`, `glue_remark`, `glue_updatetime`,
                           `child_jobid`)
VALUES (1, 1, 'Sample job 01', now(), now(), 'XXL', '', 'CRON', '0 0 0 * * ? *',
        'DO_NOTHING', 'FIRST', 'demoJobHandler', '', 'SERIAL_EXECUTION', 0, 0, 'BEAN', '', 'GLUE code init',
        now(), ''),
       (2, 2, 'Ollama sample job', now(), now(), 'XXL', '', 'NONE', '',
        'DO_NOTHING', 'FIRST', 'ollamaJobHandler', '{
    "input": "Implement level-order traversal of a binary tree in Java",
    "prompt": "You are a software engineer who is good at solving technical problems.",
    "model": "qwen3.5:0.8b"
}', 'SERIAL_EXECUTION', 0, 0, 'BEAN', '', 'GLUE code init',
        now(), ''),
       (3, 2, 'Dify sample job', now(), now(), 'XXL', '', 'NONE', '',
        'DO_NOTHING', 'FIRST', 'difyWorkflowJobHandler', '{
    "inputs":{
        "input":"Find the top three students in each subject of the class"
    },
    "user": "xxl-job",
    "baseUrl": "http://localhost/v1",
    "apiKey": "app-OUVgNUOQRIMokfmuJvBJoUTN"
}', 'SERIAL_EXECUTION', 0, 0, 'BEAN', '', 'GLUE code init',
        now(), ''),
       (4, 2, 'OpenClaw sample job', now(), now(), 'XXL', '', 'NONE', '',
        'DO_NOTHING', 'FIRST', 'openClawJobHandler', '{
    "input": "Check the weather in Shanghai today and suggest an outing plan",
    "prompt": "You are a travel assistant who is good at trip planning"
}', 'SERIAL_EXECUTION', 0, 0, 'BEAN', '', 'GLUE code init',
        now(), '');

INSERT INTO `xxl_job_user`(`id`, `username`, `password`, `role`, `permission`)
VALUES (1, 'admin', '8d969eef6ecad3c29a3a629280e686cf0c3f5d5a86aff3ca12020c923adc6c92', 1, NULL);

INSERT INTO `xxl_job_lock` (`lock_name`)
VALUES ('schedule_lock');

commit;
