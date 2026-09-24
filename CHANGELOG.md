# Changelog

All notable changes to this project are documented in this file.
Format based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/).

本文件记录本项目的所有重要更改，格式基于 [Keep a Changelog](https://keepachangelog.com/en/1.1.0/)。

## [Unreleased]

### Fixed

- Reloading this module (`/ul reload` or `/ul reload UltiBackup`) now re-reads `config/backup.yml` and refreshes the
  language files, so an edited value such as `max_backups_per_player` applies to the next backup
  without a restart. Previously this module's reload method replaced the framework's and only logged
  a line, so neither step ran and the reload reported success without reloading anything.
  UltiTools 6.3.0 also runs its `@ConditionalOnConfig` drift check at this point (this module has no
  conditional beans, so it reports nothing) and logs its own per-module reload line
  (UltiKits/UltiBackup#14).
- After `/upm uninstall UltiBackup`, this module's commands are now really removed and its listeners
  stop firing. Previously this module's unload method replaced the framework's and only logged a
  line, so both stayed active until the server restarted (UltiKits/UltiBackup#14).
- `/backup list`, the backup browser's item lore and the backup preview's info panel now label each
  backup's reason with the language file's text in the server's `language` (`Death Backup`,
  `Quit Backup`, `Auto Backup`, `Manual Backup`, `Admin Backup`, or `Unknown` for a missing or
  unrecognised value). Previously all three showed the stored constant (`DEATH`, `QUIT`, `AUTO`,
  `MANUAL`, `ADMIN`, or `UNKNOWN`) whatever the language, and the six `backup.reason.*` language
  keys were never used (UltiKits/UltiBackup#15).
- 重载本模块（`/ul reload` 或 `/ul reload UltiBackup`）现在会重新读取 `config/backup.yml` 并刷新语言文件，修改后的
  `max_backups_per_player` 等配置无需重启即可对下一次备份生效。此前本模块的重载方法替换了框架的重载方法且
  只输出一行日志，这两步都不会执行，重载报告成功却什么也没有重载。UltiTools 6.3.0 还会在此时执行
  `@ConditionalOnConfig` 漂移检查（本模块没有条件注册的 Bean，因此不会报告任何内容）并输出框架自身的模块重载日志
  （UltiKits/UltiBackup#14）。
- 执行 `/upm uninstall UltiBackup` 后，本模块的命令现在会被真正移除，其监听器也不再触发。此前本模块的卸载
  方法替换了框架的卸载方法且只输出一行日志，因此两者都会一直保持生效，直到服务器重启
  （UltiKits/UltiBackup#14）。
- `/backup list`、备份浏览界面物品说明和备份预览信息面板现在按服务器的 `language` 用语言文件中的文字标注
  每个备份的原因（`死亡备份`、`退出备份`、`自动备份`、`手动备份`、`管理员备份`，缺失或无法识别时为 `未知`）。
  此前三处都显示存储的常量（`DEATH`、`QUIT`、`AUTO`、`MANUAL`、`ADMIN` 或 `UNKNOWN`），不随语言变化，
  六个 `backup.reason.*` 语言键从未被使用（UltiKits/UltiBackup#15）。

### Removed

- The module's own console lines `UltiBackup has been disabled!` (on unload) and
  `UltiBackup configuration reloaded!` (on `/ul reload` or `/ul reload UltiBackup`). Both were English literals, not
  language keys, so no language file changes. UltiTools 6.3.0 logs one reload line per module
  (`Module 'UltiBackup' reloaded.`) (UltiKits/UltiBackup#14).
- 移除本模块自身的控制台行 `UltiBackup has been disabled!`（卸载时）和
  `UltiBackup configuration reloaded!`（`/ul reload` 或 `/ul reload UltiBackup` 时）。两者均为英文字面量而非语言键，
  因此语言文件没有变化。UltiTools 6.3.0 会为每个模块输出一行重载日志（`Module 'UltiBackup' reloaded.`）（UltiKits/UltiBackup#14）。
