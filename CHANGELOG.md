# Changelog

All notable changes to this project are documented in this file.
Format based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/).

本文件记录本项目的所有重要更改，格式基于 [Keep a Changelog](https://keepachangelog.com/en/1.1.0/)。

## [Unreleased]

### Fixed

- Restoring a backup whose items cannot be read no longer empties the player's inventory and reports
  success; the restore is refused and the inventory is left as it was. A backup file that is not
  valid YAML, that has no inventory section, or that is cut off before its end, is refused as a load
  failure, and a part holding a slot outside the inventory it is restored to cannot be read. When experience is
  restored, a negative level or a progress outside 0-1 is refused the same way, before anything changes (its
  part is named `expLevel` or `expProgress`). The console names the
  backup, the player and the part that could not be read, and the backup preview logs an unreadable
  part the same way (UltiKits/UltiBackup#21).
- 修复：恢复一个物品数据无法读取的备份时，不再清空玩家背包并报告成功；恢复被拒绝，背包保持原样。
  不是合法 YAML、缺少物品栏部分或在结尾之前被截断的备份文件按加载失败处理；某一部分中超出其所恢复到的物品栏范围的槽位视为无法读取。恢复经验时，负的等级或 0-1 之外的经验进度同样在任何改动之前被拒绝（部分名为 `expLevel` 或 `expProgress`）。控制台会写明备份、玩家和无法读取的部分，
  备份预览遇到无法读取的部分也会同样记录（UltiKits/UltiBackup#21）。
- Restoring a backup no longer destroys the armor and off-hand item the player is wearing when there
  is no armor to put back: with `backup_armor: false`, or with a backup taken while it was `false`,
  the restore replaces only the inventory's storage slots (UltiKits/UltiBackup#25).
- 修复：没有可恢复的护甲时（`backup_armor: false`，或备份是在该设置为 `false` 时创建的），恢复备份不再销毁玩家
  正穿戴的护甲与副手物品；恢复只替换背包的存储格（UltiKits/UltiBackup#25）。

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
- Nine of this module's console lines now come from the language files and follow the framework's
  `language` setting: the enable line, backup created, backup creation failed, a backup's checksum
  that could not be read, a backup's content that could not be loaded, restore done, restore failed,
  the automatic-backup summary, and the force-restore warning. Previously they were English whatever
  `language` said; the English wording is unchanged.
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
- 本模块的九条控制台日志现在取自语言文件，随框架的 `language` 设置变化：启用日志、备份已创建、备份创建失败、
  备份校验和无法读取、备份内容无法加载、恢复成功、恢复失败、自动备份汇总，以及强制恢复警告。此前无论 `language`
  如何设置都是英文；英文措辞不变。

### Removed

- The module's own console lines `UltiBackup has been disabled!` (on unload) and
  `UltiBackup configuration reloaded!` (on `/ul reload` or `/ul reload UltiBackup`). Both were English literals, not
  language keys, so no language file changes. UltiTools 6.3.0 logs one reload line per module
  (`Module 'UltiBackup' reloaded.`) (UltiKits/UltiBackup#14).
- 移除本模块自身的控制台行 `UltiBackup has been disabled!`（卸载时）和
  `UltiBackup configuration reloaded!`（`/ul reload` 或 `/ul reload UltiBackup` 时）。两者均为英文字面量而非语言键，
  因此语言文件没有变化。UltiTools 6.3.0 会为每个模块输出一行重载日志（`Module 'UltiBackup' reloaded.`）（UltiKits/UltiBackup#14）。
