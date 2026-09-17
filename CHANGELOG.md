# Changelog

All notable changes to this project are documented in this file.
Format based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/).

本文件记录本项目的所有重要更改，格式基于 [Keep a Changelog](https://keepachangelog.com/en/1.1.0/)。

## [Unreleased]

### Fixed

- Reloading this module (`/ul reload UltiBackup`) now re-reads `config/backup.yml` and refreshes the
  language files, so an edited value such as `max_backups_per_player` applies to the next backup
  without a restart. Previously this module's reload method replaced the framework's and only logged
  a line, so neither step ran and the reload reported success without reloading anything.
  UltiTools 6.3.0 also runs its `@ConditionalOnConfig` drift check at this point (this module has no
  conditional beans, so it reports nothing) and logs its own per-module reload line
  (UltiKits/UltiBackup#14).
- Unloading this module (`/upm uninstall UltiBackup`, or server shutdown) now runs the framework's
  command unregistration, then its listener unregistration. Previously this module's unload method
  replaced the framework's and only logged a line, so its commands were never unregistered on any
  unload path, and its listeners were not unregistered on `/upm uninstall` (UltiKits/UltiBackup#14).
- 重载本模块（`/ul reload UltiBackup`）现在会重新读取 `config/backup.yml` 并刷新语言文件，修改后的
  `max_backups_per_player` 等配置无需重启即可对下一次备份生效。此前本模块的重载方法替换了框架的重载方法且
  只输出一行日志，这两步都不会执行，重载报告成功却什么也没有重载。UltiTools 6.3.0 还会在此时执行
  `@ConditionalOnConfig` 漂移检查（本模块没有条件注册的 Bean，因此不会报告任何内容）并输出框架自身的模块重载日志
  （UltiKits/UltiBackup#14）。
- 卸载本模块（`/upm uninstall UltiBackup` 或关闭服务器）现在会由框架注销命令，再注销监听器。此前本模块的
  卸载方法替换了框架的卸载方法且只输出一行日志，因此任何卸载途径都不会注销其命令，`/upm uninstall`
  也不会注销其监听器（UltiKits/UltiBackup#14）。

### Removed

- The module's own console lines `UltiBackup has been disabled!` (on unload) and
  `UltiBackup configuration reloaded!` (on `/ul reload UltiBackup`). Both were English literals, not
  language keys, so no language file changes. UltiTools 6.3.0 logs one reload line per module
  (`Module 'UltiBackup' reloaded.`) (UltiKits/UltiBackup#14).
- 移除本模块自身的控制台行 `UltiBackup has been disabled!`（卸载时）和
  `UltiBackup configuration reloaded!`（`/ul reload UltiBackup` 时）。两者均为英文字面量而非语言键，
  因此语言文件没有变化。UltiTools 6.3.0 会为每个模块输出一行重载日志（UltiKits/UltiBackup#14）。
