# Changelog

All notable changes to this project are documented in this file.
Format based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/).

本文件记录本项目的所有重要更改，格式基于 [Keep a Changelog](https://keepachangelog.com/en/1.1.0/)。

## [Unreleased]

### Changed

- Automatic backups now follow a new key, `auto_backup.interval_seconds` in `config/backup.yml`, in seconds
  (default 1800, the same 30 minutes as before; 1 to 107374182). The interval is scheduled by UltiTools 6.3.0's
  config-bound scheduling: a changed value applies at `/ul reload` without moving the next backup earlier or later
  than the new interval allows, and an out-of-range value is refused, keeping the running interval, with the reload
  reported as partial. The first automatic backup still comes one interval after the module starts. The old minutes
  key `auto_backup.interval` no longer takes effect; it is never rewritten or converted, because a value of `30`
  read as seconds would have meant a backup every 30 seconds. If it holds anything other than its default 30, the
  module logs one warning at load, in the server's language, naming `auto_backup.interval_seconds`. A server's
  existing file gains the new key with its default at the first start; nothing it already holds is changed.
- 自动备份现在按新的键 `config/backup.yml` 中的 `auto_backup.interval_seconds` 执行，单位为秒（默认 1800，即与之前相同的
  30 分钟；范围 1 到 107374182）。间隔由 UltiTools 6.3.0 的配置绑定调度负责：修改后的值在 `/ul reload` 时生效，下一次备份
  不会因此提前或推迟到新间隔允许的范围之外；超出范围的值会被拒绝，保持当前运行的间隔，并将重载报告为部分完成。第一次自动备份
  仍在模块启动后一个间隔时执行。旧的分钟键 `auto_backup.interval` 不再生效，也从不被改写或换算，因为把 `30` 当作秒读取
  将意味着每 30 秒备份一次。若它不是默认值 30，模块会在加载时按服务器语言警告一次，并指出 `auto_backup.interval_seconds`。
  服务器已有的配置文件会在首次启动时加入带默认值的新键，其中已有的内容不会改变。

- This version requires UltiTools 6.3.0 or later and declares `api-version: 630` in `plugin.yml` (it was `621`).
  The config-bound scheduling above exists only from UltiTools 6.3.0: an older framework would ignore it and run
  the automatic backup once at load instead of on the interval, so an older framework now refuses to load the
  module and logs a warning instead. The README's framework minimum and its server and Java badges now say
  UltiTools 6.3.0+, Paper 1.21+ and Java 21+ (UltiKits/UltiTools-Reborn#544).
- 本版本需要 UltiTools 6.3.0 或更高版本，并在 `plugin.yml` 中声明 `api-version: 630`（原为 `621`）。上述配置绑定调度
  只在 UltiTools 6.3.0 及以上存在：更早的框架会忽略它，只在加载时执行一次自动备份而不是按间隔执行，因此现在更早的框架会
  拒绝加载本模块并记录一条警告。README 中的框架最低版本以及服务端与 Java 徽章已改为 UltiTools 6.3.0+、Paper 1.21+、
  Java 21+（UltiKits/UltiTools-Reborn#544）。

- `plugin.yml` now declares `identify-string: ultibackup`, the key of this module's entry in the UltiCloud
  catalogue. The framework's update check and `/upm update` skip a module that does not declare it, so this module
  now takes part in both: a later published version carrying the same key is reported at startup and can be
  installed with `/upm update` (UltiKits/UltiTools-Reborn#474).
- `plugin.yml` 现在声明 `identify-string: ultibackup`，即本模块在 UltiCloud 模块目录中的条目键。框架的更新检查和
  `/upm update` 会跳过未声明该键的模块，因此本模块现在会参与两者：带有同一键的更高发布版本会在启动时提示，
  并可用 `/upm update` 安装（UltiKits/UltiTools-Reborn#474）。

### Fixed

- Deleting a backup now removes its database row first and its content file after it, so a failed database
  delete no longer leaves a row pointing at a missing file. Deleting a backup whose row is already gone (for
  example two admins deleting the same backup from open menus) now answers `Backup not found!` instead of
  `Backup deleted!`. If the file cannot be deleted after its row is gone, the console names the file.
  A database failure while saving a new backup now removes the backup file that was just written, once a lookup
  confirms no row was stored, and tells the player the backup failed, instead of leaving an orphan file and no
  reply; if the row was stored after all, or the lookup cannot tell, the file is kept and the console says so. A failure while pruning old backups
  after a new one was saved is logged and no longer reports the saved backup as failed (UltiKits/UltiBackup#29).
- 删除备份时现在先删除数据库记录、再删除内容文件，数据库删除失败不再留下指向已丢失文件的记录。删除一个记录已不存在的备份
  （例如两名管理员在各自打开的菜单中删除同一个备份）时，现在提示"找不到备份"而不是"备份已删除"；记录删除后若文件无法删除，
  控制台会写出该文件。保存新备份时数据库出错，现在会在查询确认没有写入记录后删除刚写入的备份文件并告知玩家备份失败，不再留下孤立文件且无任何回复；
  若记录其实已写入，或无法确认，则保留文件并在控制台说明；
  新备份保存后清理旧备份出错时只记录日志，不再把已保存的备份报告为失败（UltiKits/UltiBackup#29）。

- A backup taken with `backup_exp: false` no longer resets the player's experience to level 0 when it is
  restored after `backup_exp` was turned on. Such a backup now holds no experience at all, and restoring
  it leaves experience as it is; its preview shows the level as not backed up. Backup files written by
  earlier versions still load, and still restore the experience they carry (UltiKits/UltiBackup#27).
- 在 `backup_exp: false` 时创建的备份，在之后开启 `backup_exp` 再恢复时，不再把玩家经验重置为 0 级。这类备份现在完全不含经验，
  恢复时保持玩家当前经验不变；其预览中等级显示为未备份。旧版本写出的备份文件仍可加载，并照旧恢复其中的经验
  （UltiKits/UltiBackup#27）。

- The automatic-backup interval in `config/backup.yml` now takes effect. Before, automatic backups ran every
  30 minutes whatever the file said: `auto_backup.interval` was read by nothing. The interval is now set by
  `auto_backup.interval_seconds` (see "Changed" above), and the first automatic backup comes one interval after
  the module starts; it used to run as the module started (UltiKits/UltiBackup#24).
- `config/backup.yml` 中的自动备份间隔现在会生效。此前无论文件写多少，自动备份都每 30 分钟执行一次：没有任何代码读取
  `auto_backup.interval`。间隔现在由 `auto_backup.interval_seconds` 设置（见上方"Changed"），第一次自动备份在模块启动后
  一个间隔时执行；此前模块启动时就会立即执行一次（UltiKits/UltiBackup#24）。

- `/backup create`, `/backup saveall` and `/backup admin create <player>` now read the player's
  inventory, armor, off-hand, ender chest and experience on the server's main thread, at the moment the
  command runs; only writing the file and the database row happens in the background. The whole
  command used to run in the background, so a backup could capture an inventory while it was changing
  (UltiKits/UltiBackup#13).
- `/backup create`、`/backup saveall` 与 `/backup admin create <玩家>` 现在在命令执行时于服务器主线程读取玩家的背包、护甲、副手、
  末影箱与经验，只有写入文件和数据库在后台进行。此前整个命令都在后台运行，备份可能在背包变化的同时读取到不一致的内容
  （UltiKits/UltiBackup#13）。

- Restoring a backup whose items cannot be read no longer empties the player's inventory and reports
  success; the restore is refused and the inventory is left as it was. A backup file that is not
  valid YAML, that has no inventory section, that is cut off before its end, or whose entries are not
  what this module writes (a part that is not text, armor without the off-hand entry or the reverse,
  an experience value that is missing or not a number), is refused as a load
  failure, and a part holding a slot outside the inventory it is restored to cannot be read. When experience is
  restored, a negative level or a progress outside 0-1 is refused the same way, before anything changes (its
  part is named `expLevel` or `expProgress`). The console names the
  backup, the player and the part that could not be read, and the backup preview logs an unreadable
  part the same way (UltiKits/UltiBackup#21).
- 修复：恢复一个物品数据无法读取的备份时，不再清空玩家背包并报告成功；恢复被拒绝，背包保持原样。
  不是合法 YAML、缺少物品栏部分、在结尾之前被截断，或条目不是本模块写入的形式（某部分不是文本、有护甲而无副手条目或反之、经验值缺失或不是数字）的备份文件按加载失败处理；某一部分中超出其所恢复到的物品栏范围的槽位视为无法读取。恢复经验时，负的等级或 0-1 之外的经验进度同样在任何改动之前被拒绝（部分名为 `expLevel` 或 `expProgress`）。控制台会写明备份、玩家和无法读取的部分，
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
