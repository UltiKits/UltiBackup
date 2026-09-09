# UltiBackup — Feature Inventory

This document catalogues every operator- or player-visible function, command, content item and
configuration key in this repository, as read directly from source. It is an internal reference
for UAT execution and issue reconciliation — the public description of these features lives on
<https://doc.ultikits.com/>. Update this file in the same pull request as any feature change.

## Conventions

- **ID grammar:** `<repo-slug>.<area>.<action>`, dot-separated, every segment lowercase ASCII
  drawn from `[a-z0-9-]`. `<repo-slug>` is the repository name lowercased with no separators —
  `ultibackup` here, `ultichat`, `ultitools`, `ultiessentials`, and `ultitools-example` for
  `UltiTools-External-Example`. `<area>` is the feature section's slug. `<action>` is the verb.
  A `config` row is the one shape that exceeds three segments and is exempt from the
  lowercase-ASCII rule for its key-path suffix:
  `<repo-slug>.config.<file-stem>.<yml key path>`, the key path keeping its own dots and its own
  casing verbatim from the yml file — a config ID is a citation of the key, not a re-derived slug,
  so lowercasing it would make it un-greppable against its own source line. An ID changes only
  when the feature's identity changes, never on rewording. IDs are unique within a repository.
- **Kind**, exactly these eight values: `command`, `config`, `event`, `gui`, `scheduled`,
  `placeholder`, `persistence`, `gate`. Each maps one-to-one onto a reconciliation-table line.
  This module has no `placeholder` rows (it neither consumes nor registers a PlaceholderAPI
  expansion) and no `gate` rows (its `@ConditionalOnConfig` count is 0 — every bean registers
  unconditionally) — both Kinds stay in the vocabulary for cross-repository consistency even
  though neither appears below.
- **Tier**, exactly three: `player`, `admin`, `internal`. Judged from what the feature is for,
  not from whether it carries a permission string — this module's single `@CmdExecutor`
  (`BackupCommand`) declares one class-level permission (`ultibackup.use`) shared by all nine
  `@CmdMapping` sites, so judging by the string alone would make every row identical; tier
  instead follows whether the row acts on the sender's own backups (`player`) or on another
  player's, or across every online player (`admin`).
- **Manual**, exactly three: `detailed`, `brief`, `none`.
- **Target**, exactly four: `player`, `console`, `both`, or `n/a` — the first three read straight
  off `@CmdTarget` for a `command` row; it is a property, not a tier. `n/a` is for every other
  Kind (`config`, `event`, `gate`, `gui`, `persistence`, `scheduled`, `placeholder`). This
  module's single `@CmdExecutor` class carries `@CmdTarget(CmdTargetType.PLAYER)`, so all nine
  command rows below read `player` — there is no console-usable command in this module at all.
- **Permission:** the literal node string, `none`, or `n/a`, each optionally suffixed with the
  literal text `(requireOp=true)` when the row's class-level `@CmdExecutor` carries that flag.
  `BackupCommand` does not set `requireOp`, so no row below carries the suffix. Four of the nine
  command rows (`create`, `saveall`, `admin`, `admin create`) additionally run a hand-coded
  `Player#hasPermission(...)` check inside the method body for a SECOND, finer-grained node
  (`ultibackup.create`, `ultibackup.admin` — twice, `ultibackup.auto` is checked in
  `BackupService`/`BackupListener`, not in a command body) — this is not the framework's
  declared `@CmdMapping(permission=)` mechanism (grep confirms zero uses of it in this class), so
  the Permission column below states only the one node `PermissionValidator` actually enforces
  for every row (`ultibackup.use`), and each affected row's Feature text names its own additional
  in-body check by name so a reader does not have to open the source to find it.
- **Source:** `ClassName#member` — the class and member that actually reads or applies the
  feature — for every Kind, `config` included: all 8 `config` rows below cite the reading
  member, not `BackupConfig`'s own field declaration (which merely binds the key and does not by
  itself say what the running plugin does with the value). This module's five `config`-adjacent
  behaviours (auto-backup toggle/interval, per-death/per-quit toggles, armor/enderchest/exp
  inclusion, and the per-player cap) are read only through `BackupConfig`'s Lombok-generated
  getters, called from `BackupService`/`BackupListener` — there is no second reading site to
  choose between.
- **Row order:** by section, then by ID ascending within the section.
- **No manual prose:** no troubleshooting column, no explanatory paragraphs, no draft page text.
  A hazard noticed while reading becomes a negative checklist row, not a note here. Where a
  feature's actual runtime behaviour genuinely diverges from what its own code comment or the
  public doc page describes it as doing (a dead lang key, a lifecycle override that skips its
  parent), that fact is itself part of "what the feature does" and is stated here as a plain,
  sourced observation, with the filed issue number, never as advice on how to fix it.

### Reconciliation command family

The canonical form for counting an annotation site across this repository's real sources:

```bash
find <repo-root> -path '*/src/main/java/*' -name '*.java' -not -path '*/target/*' \
  -not -path '*/.worktrees/*' -print0 | xargs -0 grep -nE '^[[:space:]]*@AnnotationName\b' | wc -l
```

This form defeats three measured traps, each of which produces a wrong-but-plausible number
rather than an error:

1. **Multi-root repositories** — UltiBot's sources live under `ultibot-api/`, `ultibot-core/`
   and `ultibot-v1_21_R1/`, so a naive `<repo>/src/main/java` glob returns 0 for it, silently.
   This module is a single-root Maven project (`src/main/java` only), so this trap does not
   apply to it, but the robust `find` form is used regardless — the same command must work
   unmodified across all 18 repositories.
2. **Git worktrees and build output** — UltiEconomy carries
   `.worktrees/economy-v2/src/main/java`, reporting double the real `@CmdMapping` count without
   the `-not -path` exclusions above. This module carries no worktree directory.
3. **Javadoc and string literals** — requiring the annotation to start its own line (the
   `^[[:space:]]*@` anchor) is what defeats a javadoc mention such as this module's own class
   comment on `BackupCommand` ("Uses BaseCommandExecutor with @CmdCD, @RunAsync annotations") —
   an unanchored grep for `@RunAsync` would count that prose line as a fourth hit against the
   three real annotation sites below.

**GUI page classes are not found by grepping for an annotation at all** — none of this
repository's three GUI classes carries a page-marking annotation; they are identified
structurally (implementing `InventoryHolder`, or extending
`com.ultikits.ultitools.abstracts.gui.BaseConfirmationPage`) under the module's own `gui`
package. The counting command for this Kind, applied here as `## GUI` section's own
reconciliation note:

```bash
find <repo-root>/src/main/java -path '*/gui/*' -name '*.java' -not -path '*/target/*' | wc -l
```

**Positive control:** the line-start form returns `@CmdExecutor` = 1, `@CmdMapping` = 9,
`@EventListener` = 1 (class), `@EventHandler` = 4 (handler methods), `@Scheduled` = 1,
`@ConditionalOnConfig` = 0, `@ConfigEntity` = 1, `@ConfigEntry` = 8, `@Table` = 1 — confirmed by
reading `BackupCommand.java` directly (9 `@CmdMapping` sites at lines 51, 62, 90, 112, 131, 152,
171, 196, 228: `` (bare), `list`, `create`, `restore <number>`, `restore <number> force`,
`saveall`, `admin <player>`, `admin create <player>`, `help`) and `BackupConfig.java` (8
`@ConfigEntry` sites at lines 24, 28, 31, 34, 38, 41, 44, 47). The `find`-based GUI-class count
above returns 3, matching Phase 9's own independently-derived GUI-exclusion register for this
module (`BackupGUI`, `BackupPreviewGUI`, `ForceRestoreConfirmPage` — see
`.planning/phases/09-module-ecosystem-readiness-and-test-coverage/gui-exclusions/UltiBackup.md`),
confirmed by reading all three files directly. This document's command-row count matches the
`@CmdMapping` annotation-site count exactly (9 against 9) — unlike the framework's own
`/upm`/`/ulticloud` sections, this module has no bare-`help`-argument `@CmdMapping`-free dispatch
path documented as an extra row: `help` is itself an explicit `@CmdMapping(format = "help")` site
(line 228) that simply delegates to `handleHelp`, not a `BaseCommandExecutor#onCommand`
short-circuit.

**Reconciliation note — event Kind (4 handler methods against 2 `event`-Kind rows below):** this
is a deliberate, explained mismatch, not an omission. `BackupListener` carries 4
`@EventHandler` methods; only `onPlayerDeath` and `onPlayerQuit` are catalogued as `event`-Kind
rows in `## Auto-Backup Triggers` below. The other two (`onBackupGUIClick`, `onPreviewGUIClick`)
are the click-routing implementation *for* this module's `gui`-Kind rows, not independent
player-visible behaviours of their own — a player experiences "click a backup item in the
browser GUI", not "an inventory-click event fired". Each is instead named, by method, in the
`gui`-Kind row's own Feature text in `## GUI` below, so the reconciliation table's `@EventListener`
line states the true handler-method count (4) against the row count actually attributable to the
`event` Kind (2), with this note as the stated reason for the other two.

## Backup and Restore Commands

`BackupCommand` — class-level `@CmdExecutor(alias = {"backup", "invbackup", "bk"}, permission =
"ultibackup.use", description = "backup.command.description")`, `@CmdTarget(PLAYER)`. All nine
`@CmdMapping` sites below live on this one class.

| ID | Feature | Kind | How to reach | Permission | Target | Tier | Manual | Source |
|---|---|---|---|---|---|---|---|---|
| ultibackup.backup.admin-create | Create a manual backup for another (online) player; additionally requires the sender hold `ultibackup.admin` (a hand-coded body check, not a declared `@CmdMapping` permission — see Conventions) | command | `/backup admin create <player>` | ultibackup.use | player | admin | brief | BackupCommand#adminCreateBackup |
| ultibackup.backup.admin-view | Open the backup browser GUI for another player's (online or offline) backups; additionally requires the sender hold `ultibackup.admin` (hand-coded body check) | command | `/backup admin <player>` | ultibackup.use | player | admin | brief | BackupCommand#adminBackups |
| ultibackup.backup.create | Create a manual backup of the sender's own inventory/armor/ender chest/experience (per `BackupConfig`'s three include toggles); additionally requires the sender hold `ultibackup.create` (hand-coded body check); reads the player's live inventory off the main thread despite `@RunAsync` — a known product defect, `UltiKits/UltiBackup#13` | command | `/backup create` | ultibackup.use | player | player | brief | BackupCommand#createBackup |
| ultibackup.backup.help | Print the `/backup` command usage summary, with three admin-only lines shown only to a sender holding `ultibackup.admin` | command | `/backup help` | ultibackup.use | player | player | none | BackupCommand#help |
| ultibackup.backup.list | List the sender's own backups (up to 5, newest first, with an overflow count for the rest) | command | `/backup list` | ultibackup.use | player | player | brief | BackupCommand#listBackups |
| ultibackup.backup.open | Open the paginated backup browser GUI (`ultibackup.gui.backup-browser`) for the sender's own backups; this is the module's default (bare-argument) command | command | `/backup` (bare, no arguments) | ultibackup.use | player | player | brief | BackupCommand#openBackups |
| ultibackup.backup.restore | Restore one of the sender's own backups by its list position, after SHA-256 checksum verification; on a checksum mismatch, reports failure and does NOT restore — the sender must separately run the `.restore-force` command below to override | command | `/backup restore <number>` | ultibackup.use | player | player | brief | BackupCommand#restoreBackup |
| ultibackup.backup.restore-force | Force-restore one of the sender's own backups by list position, SKIPPING checksum verification, through a confirmation dialogue (`ultibackup.gui.force-restore-confirm`) rather than restoring immediately | command | `/backup restore <number> force` | ultibackup.use | player | player | brief | BackupCommand#forceRestoreBackup |
| ultibackup.backup.saveall | Create a manual backup for every currently online player who holds `ultibackup.auto`; additionally requires the sender hold `ultibackup.admin` (hand-coded body check); reads player inventories off the main thread despite `@RunAsync`, the same known defect as `.create` above, `UltiKits/UltiBackup#13` | command | `/backup saveall` | ultibackup.use | player | admin | brief | BackupCommand#saveAllPlayers |

## GUI

Three GUI page classes, none carrying a page-marking annotation — identified structurally (see
Conventions' own reconciliation note for this Kind). All three are Phase 9's complete
GUI-exclusion register for this module; each is named on exactly one row below and again in its
`UAT-CHECKLIST.md` Covers cell.

| ID | Feature | Kind | How to reach | Permission | Target | Tier | Manual | Source |
|---|---|---|---|---|---|---|---|---|
| ultibackup.gui.backup-browser | Paginated (45-per-page) chest GUI listing the target player's backups, newest first; left-click restores, shift+left-click opens the preview GUI, right-click deletes (requires `ultibackup.delete` or `ultibackup.admin`); a create-new-backup button (slot 47) and page navigation (slots 45/53) round out the bottom row. Click routing lives entirely in `BackupListener#onBackupGUIClick`, not in this class — the class itself only renders | gui | opened by `ultibackup.backup.open` or `ultibackup.backup.admin-view` | n/a | n/a | player | brief | BackupGUI#updateInventory, BackupListener#onBackupGUIClick |
| ultibackup.gui.backup-preview | Read-only, tabbed (inventory / armor+offhand / ender chest) preview of one backup's stored contents, without restoring anything; a close button returns to nothing (simply closes the inventory). Click routing lives in `BackupListener#onPreviewGUIClick` | gui | shift+left-click a backup item in `ultibackup.gui.backup-browser` | n/a | n/a | player | brief | BackupPreviewGUI#updateInventory, BackupListener#onPreviewGUIClick |
| ultibackup.gui.force-restore-confirm | Confirmation dialogue (extends the framework's `BaseConfirmationPage`, the only one of this module's three GUI classes to use it rather than a raw `InventoryHolder`) warning that the target backup failed checksum verification and that force-restoring risks item loss or corruption; Confirm performs the restore, Cancel aborts with no state change | gui | `ultibackup.backup.restore-force` (the `/backup restore <number> force` command path), OR left-clicking a backup item in `ultibackup.gui.backup-browser` whose checksum verification fails — that click path is `BackupListener#handleRestore` specifically, NOT the plain `/backup restore <number>` command: `BackupCommand#handleRestore` (a separate, identically-named private method on a different class) only sends the checksum-failure chat lines on a CHECKSUM_FAILED result and never opens this dialogue at all, so a checksum failure reached via the bare command (without `force`) does NOT route here | n/a | n/a | player | detailed | ForceRestoreConfirmPage#setupDialogContent, ForceRestoreConfirmPage#onConfirm, ForceRestoreConfirmPage#onCancel |

## Auto-Backup Triggers

`BackupListener` — class-level `@EventListener`. Two of this class's four `@EventHandler`
methods are catalogued here (see the Conventions section's own reconciliation note for the other
two, which are `ultibackup.gui.*`'s own click-routing implementation, not independent
player-visible events).

| ID | Feature | Kind | How to reach | Permission | Target | Tier | Manual | Source |
|---|---|---|---|---|---|---|---|---|
| ultibackup.event.auto-backup-on-death | Automatically create a backup (reason `DEATH`) for a player who dies, before death drops are processed, provided the player holds `ultibackup.auto` and `auto_backup.on_death` is enabled | event | die while holding `ultibackup.auto` and `auto_backup.on_death: true` | n/a | n/a | internal | brief | BackupListener#onPlayerDeath |
| ultibackup.event.auto-backup-on-quit | Automatically create a backup (reason `QUIT`) for a player who disconnects, provided the player holds `ultibackup.auto` and `auto_backup.on_quit` is enabled | event | quit the server while holding `ultibackup.auto` and `auto_backup.on_quit: true` | n/a | n/a | internal | brief | BackupListener#onPlayerQuit |

## Module Reload

| ID | Feature | Kind | How to reach | Permission | Target | Tier | Manual | Source |
|---|---|---|---|---|---|---|---|---|
| ultibackup.event.module-reload | Intended to reload this module's configuration from disk when the framework reloads it; in reality does nothing but log a success line — `UltiBackup#reloadSelf()` overrides the framework's `reloadSelf()` WITHOUT calling `super.reloadSelf()`, so `BackupConfig` is never re-read and the printed success message describes work that never happened. A known product defect, `UltiKits/UltiBackup#14`, not fixed here per this phase's zero-new-code rule | event | `/ul reload` or `/ul reload UltiBackup` (framework-level; this module declares no `/backup reload` subcommand of its own) | n/a | n/a | admin | brief | UltiBackup#reloadSelf |

## Scheduled Tasks

| ID | Feature | Kind | How to reach | Permission | Target | Tier | Manual | Source |
|---|---|---|---|---|---|---|---|---|
| ultibackup.task.auto-backup-all | Automatically create a backup (reason `AUTO`) for every online player who holds `ultibackup.auto`, on a fixed 36000-tick (30-minute) period, provided `auto_backup.enabled` is true | scheduled | runs automatically every 36000 ticks (30 minutes) while the server is up and `auto_backup.enabled: true` | n/a | n/a | internal | brief | BackupService#autoBackupAll |

## Data Persistence

| ID | Feature | Kind | How to reach | Permission | Target | Tier | Manual | Source |
|---|---|---|---|---|---|---|---|---|
| ultibackup.storage.backup-survives-restart | A created backup has two parts that both survive a server restart: its metadata (player, time, reason, checksum, location) via `DataOperator`/`@Table` in whichever ORM backend the framework has configured, and its actual inventory content as a SHA-256-headed YAML file under `plugins/UltiTools/backups/`; the checksum is re-verified on every restore attempt and a mismatch (tampered or corrupted file) blocks a normal restore, routing to `ultibackup.gui.force-restore-confirm` instead of silently restoring bad data | persistence | create a backup via any `ultibackup.backup.*` command, then restart the server and read it back via `/backup list` or `/backup restore` | n/a | n/a | admin | detailed | BackupMetadata#BackupMetadata, BackupContent#saveToFile, BackupContent#verifyChecksum |

## Configuration

Every `@ConfigEntry`-annotated field on this module's one `@ConfigEntity` class (8 keys total,
matching the reconciliation table's own `@ConfigEntry` count of 8 exactly). This module ships NO
`config/backup.yml` resource under `src/main/resources` — unlike the framework's own migrated
keys or UltiChat's five shipped config files, `BackupConfig`'s file is generated entirely from
these `@ConfigEntry` field defaults the first time the module boots, with no packaged seed
resource to diff against.

**One key is declared and validated but its own display method never reads it correctly:**
`getReasonDisplay()` (the method every `backup.reason.*`-consuming row would call) returns the
raw, untranslated reason constant instead of looking up one of the six `backup.reason.*` language
keys — a known product defect, `UltiKits/UltiBackup#15`, not fixed here per this phase's
zero-new-code rule. This is a language-catalogue defect, not a `@ConfigEntry` defect, so it has
no row in this section; it is called out here because every config row above documents inclusion
toggles that feed backup CONTENT, and this is the one place a reader might expect the reason
label to be config-adjacent and find it is not.

| ID | Feature | Kind | How to reach | Permission | Target | Tier | Manual | Source |
|---|---|---|---|---|---|---|---|---|
| ultibackup.config.backup.auto_backup.enabled | Enable the 30-minute automatic backup scheduler for online players holding `ultibackup.auto` | config | `config/backup.yml: auto_backup.enabled (default: true)` | n/a | n/a | admin | brief | BackupService#autoBackupAll |
| ultibackup.config.backup.auto_backup.interval | Declared as the auto-backup interval in minutes (range 1-1440, enforced by `@Range`); never read by any production code — `BackupService#autoBackupAll`'s real period is the `@Scheduled(period = 36000)` annotation's hardcoded tick count (30 minutes), regardless of this key's value | config | `config/backup.yml: auto_backup.interval (default: 30, has no effect)` | n/a | n/a | admin | brief | BackupConfig#autoBackupInterval (declared, never read outside this class) |
| ultibackup.config.backup.auto_backup.on_death | Enable auto-backup on player death (reason `DEATH`) | config | `config/backup.yml: auto_backup.on_death (default: true)` | n/a | n/a | admin | brief | BackupListener#onPlayerDeath |
| ultibackup.config.backup.auto_backup.on_quit | Enable auto-backup on player quit (reason `QUIT`) | config | `config/backup.yml: auto_backup.on_quit (default: true)` | n/a | n/a | admin | brief | BackupListener#onPlayerQuit |
| ultibackup.config.backup.backup_armor | Include the player's worn armor and off-hand item in a created backup's content | config | `config/backup.yml: backup_armor (default: true)` | n/a | n/a | admin | brief | BackupContent#fromPlayer, BackupContent#restoreToPlayer |
| ultibackup.config.backup.backup_enderchest | Include the player's ender chest contents in a created backup's content | config | `config/backup.yml: backup_enderchest (default: true)` | n/a | n/a | admin | brief | BackupContent#fromPlayer, BackupContent#restoreToPlayer |
| ultibackup.config.backup.backup_exp | Include the player's experience level and progress in a created backup's content | config | `config/backup.yml: backup_exp (default: true)` | n/a | n/a | admin | brief | BackupContent#fromPlayer, BackupContent#restoreToPlayer |
| ultibackup.config.backup.max_backups_per_player | Maximum number of backups retained per player (range 1-1000, enforced by `@Range`); the oldest backups beyond this count are deleted (metadata AND content file, via `BackupMetadata#onDelete`) immediately after each new backup is created | config | `config/backup.yml: max_backups_per_player (default: 10)` | n/a | n/a | admin | brief | BackupService#cleanupOldBackups |

### Reconciliation note (@ConditionalOnConfig)

The line-start form of the canonical command reports exactly 0 `@ConditionalOnConfig` sites in
this repository — every bean this module registers (`BackupCommand`, `BackupService`,
`BackupListener`) is unconditional at component-scan time. This is the 0-against-0 line the
reconciliation table states rather than omits.
