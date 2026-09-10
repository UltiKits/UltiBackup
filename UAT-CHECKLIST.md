# UltiBackup — UAT Checklist

This document is the executable companion to `FEATURES.md`: one row per feature stating the
steps to exercise it and the observable truth that proves it works. It is an internal reference
for real-machine verification, not user-facing documentation.

> Batches are dispatched at 60 rows or fewer, and a batch never spans two repositories. There are
> exactly two legitimate exits to `human-uat-pending`: a row needing the pixel layer while the
> real-client harness is not ready, and a row needing personal credentials. Every other row must
> reach `pass`, `fail`, or `blocked`.

> **This repository runs in its own dispatch, for the same reason UltiCleaner does.** Every
> `ultibackup.backup.restore*`/`ultibackup.gui.force-restore-confirm` row below REPLACES a real
> player's live inventory, armor, off-hand item, ender chest and/or experience wholesale — state
> other modules' own checklist rows (economy balances observed via held items, trade rows,
> anything reading a player's current possessions) may depend on. Interleaving this module's
> dispatch with another module's would let a restore silently invalidate a fixture another
> module's row is mid-way through relying on.

> **Correction to this module's plan-stage description:** the plan that produced this checklist
> described UltiBackup's restore path as overwriting "world data". Reading the source
> (`BackupContent#restoreToPlayer`, `BackupService#restoreBackup`/`#forceRestore`) shows the
> actual destructive target is narrower and different in kind: a restore replaces the TARGET
> PLAYER'S OWN inventory/armor/off-hand/ender-chest/experience — it never touches world terrain,
> blocks, or any other player's state. Every Preconditions cell below is scoped to that real
> hazard (a disposable test player's own current items) rather than to world data, which this
> module's restore path cannot reach at all. As a defense-in-depth discipline anyway (this
> module cannot reach world terrain, but the same scratch/throwaway-fixture rule the plan applies
> to genuinely world-destructive modules is followed here too): every fixture below (`Tester1`,
> `Tester2`) must be a throwaway, scratch, disposable test-only player identity, and every backup
> exercised by a restore or force-restore row must itself be a scratch backup created by an
> earlier row in this same file — do not run any restore row against a real player's inventory
> on the shared server's live world.

## Conventions

- **Columns:** `ID`, `Preconditions`, `Steps`, `Expected`, `Layer`, `Covers`.
- **ID:** cites its `FEATURES.md` ID verbatim. A negative case suffixes the checklist ID only,
  as `.neg-<slug>` — a negative case still tests the same feature, so the base ID is unchanged.
- **Layer**, copied verbatim from Laojun's own `ultitools-real-client-uat` skill so no
  translation step exists at dispatch time: `protocol`, `java-client`, `os-input`, `pixel`,
  `server`, `human`.
- **Human-authenticated-session rows (D-27b):** a row whose Steps can only be exercised through
  the maintainer's own authenticated UltiCloud panel session carries the fixed Preconditions
  phrase `maintainer-authenticated UltiCloud panel session (personal credentials)` and Layer
  `human`. This module has no panel-facing surface at all (no capability, no remote route), so no
  row below is affected; the convention is stated here for template consistency.
- **Expected** must name an observable truth — an exact chat line, a log line, a database row,
  an inventory slot — and never the words "it works".
- **Covers** back-references a Phase 9 GUI-excluded class name; left blank when no such class
  applies. This module owns all three of its GUI-excluded classes (`BackupGUI`,
  `BackupPreviewGUI`, `ForceRestoreConfirmPage`) — each is named in exactly one row's Covers cell
  below.
- A row whose Preconditions name a prior row must appear after that row in file order — asserted
  mechanically: for every row, every checklist ID cited in its Preconditions cell must have a
  strictly smaller line number in this file than the row citing it (sweep class 8, D-27a).
- **Config-per-file rule (D-06):** one checklist row per `@ConfigEntity`-annotated class or per
  shipped yml file, never one row per key. This module has exactly one such file
  (`config/backup.yml`, generated from `BackupConfig`'s `@ConfigEntry` defaults — no packaged
  seed resource exists to extract), so exactly one config-per-file row appears below
  (`ultibackup.config.backup-yml`).
- This repository's shipped default is `language: "zh"` in the framework's own `config.yml`
  (this module has no `language` key of its own — it follows the framework's setting via
  `Localized#supported()`). Every row below whose Expected quotes a literal chat/console line
  therefore carries the precondition `language: en` in `plugins/UltiTools/config.yml`, so the
  observed line matches this document's English-only text exactly, character for character.
- `plugin.i18n(...)` in this framework is a raw dictionary lookup with no `MessageFormat`
  processing or placeholder-escaping beyond the module's own explicit `String#replace` calls;
  every Expected line below reproduces `lang/en.yml`'s literal text.

## Backup and Restore Commands

| ID | Preconditions | Steps | Expected | Layer | Covers |
|---|---|---|---|---|---|
| ultibackup.backup.create | `language: en`; a disposable test player (`Tester1`) holding both `ultibackup.use` and `ultibackup.create`, online | Run `/backup create` | Chat shows `✔ Backup created successfully!` (green); a new row for this player now exists in the database's `backup_metadata` table with `backup_reason = MANUAL`, and a new YAML file exists under `plugins/UltiTools/backups/` whose header block (the file's first six lines, before the YAML body) contains a `# Checksum:` line — `BackupContent#FILE_HEADER` places four warning lines and a separator before it, so the checksum line is the SIXTH line of the file, not the first | server | |
| ultibackup.backup.create.neg-no-permission | `language: en`; `Tester1` holds `ultibackup.use` but NOT `ultibackup.create` | Run `/backup create` | Chat shows `You don't have permission to do this!` (red); no new `backup_metadata` row and no new backup file are created | server | |
| ultibackup.backup.list | `language: en`; `Tester1` has exactly one backup (from `ultibackup.backup.create`) | Run `/backup list` | Chat shows `=== Your Backups ===` (gold) followed by exactly one line reading `1. <TIME> MANUAL` in the `{NUMBER}. {TIME} {REASON}` shape — `{REASON}` renders the RAW constant `MANUAL` verbatim, not a translated label, because `BackupMetadata#getReasonDisplay()` never applies the six `backup.reason.*` language keys (known product defect, `UltiKits/UltiBackup#15`); this row's Expected states the actual (untranslated) output, not the label the lang keys were written to provide | server | |
| ultibackup.backup.list.neg-empty | `language: en`; a disposable test player (`Tester2`) with zero backups (never ran `/backup create`, and has no auto-backup trigger fired for them) | Run `/backup list` | Chat shows exactly `You have no backups.` (yellow) — no header line, no item lines; this is the ONLY correct empty-state text (a bare header with zero item lines would be a vacuous pass) | server | |
| ultibackup.backup.restore | `language: en`; `Tester1` has a backup with an intact, unmodified checksum (from `ultibackup.backup.create`, run immediately beforehand so no other row has corrupted it); `Tester1`'s CURRENT inventory (before running this row) is populated with disposable, worthless items whose loss does not matter — this row REPLACES that inventory entirely with the backup's stored contents | Note `Tester1`'s current inventory contents, then run `/backup restore 1` | Chat shows `✔ Backup restored successfully!` (green); `Tester1`'s inventory now matches exactly what was captured at backup time (from `ultibackup.backup.create`), not the disposable items noted before this step — the disposable items are gone, proving a genuine overwrite occurred, not a no-op | server | |
| ultibackup.backup.restore.neg-invalid-number | `language: en`; `Tester1` has exactly one backup (backup list position 1 exists, position 2 does not) | Run `/backup restore 2` | Chat shows `Invalid backup number!` (red); `Tester1`'s inventory is unchanged | server | |
| ultibackup.backup.restore.neg-checksum-failed | `language: en`; `Tester1` has a backup (from `ultibackup.backup.create`); its on-disk content YAML file under `plugins/UltiTools/backups/` is hand-edited to change one byte inside the YAML body while leaving the `# Checksum:` header line untouched, so the stored checksum no longer matches the (now-tampered) content | Run `/backup restore 1` | Chat shows `⚠ Warning: Backup file checksum verification failed!` then `The file may have been modified or corrupted. Use force restore if you still want to proceed.` (both, and neither restores anything — `Tester1`'s CURRENT inventory is unchanged); this is `ultibackup.backup.restore`'s CHECKSUM_FAILED branch, distinct from its SUCCESS branch above | server | |
| ultibackup.backup.restore-force | `language: en`; `Tester1` has the tampered backup from `ultibackup.backup.restore.neg-checksum-failed` (checksum verification would fail if attempted) | Run `/backup restore 1 force` | The force-restore confirmation dialogue (`ultibackup.gui.force-restore-confirm`) opens for `Tester1`, showing the warning lore — this row proves ONLY that the command opens the dialogue; the dialogue's own Confirm/Cancel behaviour is covered by `ultibackup.gui.force-restore-confirm` below, not here | server | ForceRestoreConfirmPage |
| ultibackup.backup.saveall | `language: en`; a sender holding `ultibackup.use` and `ultibackup.admin`; at least one other online player (`Tester1`) holding `ultibackup.auto` | Run `/backup saveall` | Chat shows `✔ Created backups for <COUNT> players!` (green) where `<COUNT>` matches the number of online players holding `ultibackup.auto` (at least 1); `Tester1` now has one additional `backup_metadata` row with `backup_reason = ADMIN` | server | |
| ultibackup.backup.saveall.neg-no-permission | `language: en`; a sender holding `ultibackup.use` but NOT `ultibackup.admin` | Run `/backup saveall` | Chat shows `You don't have permission to do this!` (red); no new backup rows are created for any online player | server | |
| ultibackup.backup.admin-view | `language: en`; a sender holding `ultibackup.use` and `ultibackup.admin`; `Tester1` has at least one backup (from `ultibackup.backup.create`) | Run `/backup admin Tester1` | The `ultibackup.gui.backup-browser` GUI opens for the SENDER (not `Tester1`), titled with `Tester1`'s name, listing `Tester1`'s own backups — this row proves only that the command opens the correct target's browser; the browser's own interactions are covered by `ultibackup.gui.backup-browser` below | server | BackupGUI |
| ultibackup.backup.admin-view.neg-no-permission | `language: en`; a sender holding `ultibackup.use` but NOT `ultibackup.admin` | Run `/backup admin Tester1` | Chat shows `You don't have permission to do this!` (red); no inventory opens | server | |
| ultibackup.backup.admin-create | `language: en`; a sender holding `ultibackup.use` and `ultibackup.admin`; `Tester1` online | Run `/backup admin create Tester1` | Chat shows `Created backup for player Tester1!` (green); a new `backup_metadata` row for `Tester1` now exists with `backup_reason = ADMIN` | server | |
| ultibackup.backup.admin-create.neg-offline | `language: en`; a sender holding `ultibackup.use` and `ultibackup.admin`; no player named `NotOnline` is currently online | Run `/backup admin create NotOnline` | Chat shows `Player NotOnline is offline!` (red); no new backup row is created | server | |
| ultibackup.backup.help | `language: en`; sender holds `ultibackup.use` (the base class-level permission, without which the framework rejects `/backup help` before it ever dispatches) but does NOT hold `ultibackup.admin` | Run `/backup help` | Chat shows `=== UltiBackup Help ===` (gold) followed by exactly the five non-admin lines (`/backup`, `/backup list`, `/backup create`, `/backup restore <number>`, `/backup restore <number> force`, each with its own description) — the three admin-only lines (`saveall`, `admin <player>`, `admin create <player>`) do NOT appear | server | |
| ultibackup.backup.help.neg-admin | `language: en`; sender holds both `ultibackup.use` and `ultibackup.admin` | Run `/backup help` | The same five lines as above, PLUS the three admin-only lines (`saveall`, `admin <player>`, `admin create <player>`) | server | |
| ultibackup.backup.open | `language: en`; `Tester1` holds `ultibackup.use`, with zero or more existing backups | Run bare `/backup` (no arguments) | The `ultibackup.gui.backup-browser` GUI opens for `Tester1`, titled `Inventory Backup - Tester1`, listing `Tester1`'s own backups — this row proves only that the bare command opens the correct browser for the sender's own backups; the browser's own interactions are covered by `ultibackup.gui.backup-browser` below | server | BackupGUI |

## GUI

| ID | Preconditions | Steps | Expected | Layer | Covers |
|---|---|---|---|---|---|
| ultibackup.gui.backup-browser | `language: en`; `Tester1` has EXACTLY 46 to 90 backups with intact checksums (a count strictly in this range guarantees exactly 2 pages at 45 items/page — `Math.ceil(count / 45)`; 91 or more would produce 3+ pages and a different, untested page-indicator value; repeat `ultibackup.backup.create` as needed, or lower `max_backups_per_player` temporarily is NOT an option here since it would prune them; raise it instead); the browser is open via `ultibackup.backup.open` | Left-click the first (topmost, newest) backup item; separately (re-open, run this on a second attempt), shift+left-click a backup item; separately, right-click a backup item while holding `ultibackup.delete` or `ultibackup.admin`; separately, click the create-new-backup button (slot 47); separately, with the 46-90 backups present, click the next-page button (slot 53) then the previous-page button (slot 45) | Left-click restores that backup to `Tester1` exactly as `ultibackup.backup.restore` does (chat `✔ Backup restored successfully!`, inventory contents replaced) and closes the inventory; shift+left-click opens `ultibackup.gui.backup-preview` for that backup WITHOUT restoring anything (`Tester1`'s inventory is unchanged immediately afterward); right-click deletes that backup (chat `✔ Backup deleted!`, the item's row is removed from the GUI on refresh, and its `backup_metadata` row plus its content YAML file are both gone); the create-new-backup button creates a fresh backup exactly as `ultibackup.backup.create` does and the GUI refreshes to show it; with the 46-90-backup fixture, the next-page button advances to page 2 — EXACTLY 2 total pages, so the indicator reads `Page 2 / 2` — showing backups 46 through the fixture's own count (NOT open-endedly "46th-and-later"), and the previous-page button returns to page 1 | pixel | BackupGUI |
| ultibackup.gui.backup-browser.neg-no-delete-permission | `language: en`; `Tester1` holds neither `ultibackup.delete` nor `ultibackup.admin`; the browser open via `ultibackup.backup.open`, with at least one backup present | Right-click a backup item | Chat shows `You don't have permission to do this!` (red); the backup item and its underlying `backup_metadata` row are unchanged | server | BackupGUI |
| ultibackup.gui.backup-preview | `language: en`; the preview GUI open via `ultibackup.gui.backup-browser`'s shift+left-click, for a backup whose content includes armor and ender-chest items (created with `backup_armor`/`backup_enderchest` both true) | Click the Armor tab (slot 46), then the Ender Chest tab (slot 47), then the Inventory tab (slot 45), then the Close button (slot 53) | Each tab click re-renders the grid to show that view's items (armor+offhand for slot 46, ender chest for slot 47, main inventory for slot 45), with the currently-active tab's icon highlighted lime and the other two white; the info panel (slot 49) shows the backup's time/reason/level/world; Close (slot 53) closes the inventory for the viewer with no side effect on the underlying backup | pixel | BackupPreviewGUI |
| ultibackup.gui.force-restore-confirm | `language: en`; the confirmation dialogue open via `ultibackup.backup.restore-force`, for `Tester1`'s tampered backup | Click the Confirm button | Chat shows `⚠ Corrupted backup force-restored!` (yellow) to the confirming sender; `Tester1`'s inventory is replaced by the tampered backup's (still-deserializable) content DESPITE the checksum mismatch — the whole point of this dialogue is to allow exactly this override; console logs a WARNING naming the confirming player, the backup ID, and the target | server | ForceRestoreConfirmPage |
| ultibackup.gui.force-restore-confirm.neg-cancel | `language: en`; the confirmation dialogue open via `ultibackup.backup.restore-force`, for `Tester1`'s tampered backup, with `Tester1`'s current inventory noted beforehand | Click the Cancel button | Chat shows `Restore operation cancelled.` (gray) to the confirming sender; `Tester1`'s inventory is UNCHANGED from what was noted before this step — no restore occurred | server | ForceRestoreConfirmPage |

## Auto-Backup Triggers

| ID | Preconditions | Steps | Expected | Layer | Covers |
|---|---|---|---|---|---|
| ultibackup.event.auto-backup-on-death | `auto_backup.on_death: true` (shipped default); `Tester1` holds `ultibackup.auto` | `Tester1` dies (e.g. via `/kill Tester1` in creative-adjacent testing, or genuine fall damage) | A new `backup_metadata` row for `Tester1` exists with `backup_reason = DEATH`, timestamped at or immediately before the death event, with a corresponding content YAML file on disk | server | |
| ultibackup.event.auto-backup-on-death.neg-no-permission | `auto_backup.on_death: true`; `Tester2` does NOT hold `ultibackup.auto` | `Tester2` dies | No new `backup_metadata` row is created for `Tester2` as a result of this death | server | |
| ultibackup.event.auto-backup-on-quit | `auto_backup.on_quit: true` (shipped default); `Tester1` holds `ultibackup.auto`, online | `Tester1` disconnects from the server (quit, not kick) | A new `backup_metadata` row for `Tester1` exists with `backup_reason = QUIT`, timestamped at or immediately before the disconnect | server | |
| ultibackup.event.auto-backup-on-quit.neg-disabled | `auto_backup.on_quit: false` (NOT the shipped default); `Tester1` holds `ultibackup.auto`, online | `Tester1` disconnects from the server | No new `backup_metadata` row is created for `Tester1` as a result of this quit | server | |

## Module Reload

| ID | Preconditions | Steps | Expected | Layer | Covers |
|---|---|---|---|---|---|
| ultibackup.event.module-reload | `language: en`; `Tester1` has at least one existing backup (any prior row); note `max_backups_per_player`'s CURRENT effective value (10, the shipped default, unless a config row above changed it) | Hand-edit `config/backup.yml`'s `max_backups_per_player` to a new, distinguishable value (e.g. `3`) on disk; run `/ul reload`; then create backups past the OLD limit and observe whether pruning follows the old or the new value | Console/chat reports the module reloaded successfully (via the framework's own `/ul reload` output, per `ultitools.ul.reload`); `UltiBackup#reloadSelf()` logs `UltiBackup configuration reloaded!`; BUT the edited `max_backups_per_player` value has NO effect — pruning still triggers at the OLD limit, not the new one, because `reloadSelf()` never calls `super.reloadSelf()` and so `BackupConfig` is never re-read from disk. A known product defect, `UltiKits/UltiBackup#14` — this row documents the actual (broken) behaviour, not the intended one | server | |

## Scheduled Tasks

| ID | Preconditions | Steps | Expected | Layer | Covers |
|---|---|---|---|---|---|
| ultibackup.task.auto-backup-all | `auto_backup.enabled: true` (shipped default); at least one online player (`Tester1`) holding `ultibackup.auto` | Wait one 36000-tick (30-minute) interval with `Tester1` online | A new `backup_metadata` row for `Tester1` exists with `backup_reason = AUTO`, timestamped at the interval boundary | server | |
| ultibackup.task.auto-backup-all.neg-disabled | `auto_backup.enabled: false` (NOT the shipped default); at least one online player holding `ultibackup.auto` | Wait one 36000-tick interval | No new `backup_metadata` row with `backup_reason = AUTO` is created for that interval | server | |

## Data Persistence

| ID | Preconditions | Steps | Expected | Layer | Covers |
|---|---|---|---|---|---|
| ultibackup.storage.backup-survives-restart | `Tester1` has EXACTLY ONE backup created via `ultibackup.backup.create`, with an intact (untampered) checksum (a single backup fixes its list position at `1`, so the restore step below has an unambiguous `<number>`) | Stop the server completely, then start it again, then run `/backup list` as `Tester1`, then run `/backup restore 1` | The backup still appears in `/backup list` with its original time and reason; the content YAML file is still present on disk with an unchanged `# Checksum:` header; `/backup restore 1` succeeds (chat `✔ Backup restored successfully!`) — the checksum verification and the content load both genuinely ran against the post-restart, on-disk state, not merely a list read | server | |
| ultibackup.storage.backup-survives-restart.neg-tampered | `Tester1` has EXACTLY ONE backup whose content YAML file has been hand-edited (one byte changed inside the YAML body, header untouched) BEFORE the restart, per the same tampering technique as `ultibackup.backup.restore.neg-checksum-failed` (a single backup fixes its list position at `1`) | Stop the server completely, then start it again, then run `/backup restore 1` as `Tester1` | The metadata row and file both still exist after restart (nothing is auto-deleted), but `/backup restore 1` still reports the checksum failure (`⚠ Warning: Backup file checksum verification failed!`) exactly as it did before the restart — corruption detection is not itself lost across a restart | server | |

## Configuration

One row per shipped yml file (D-06's config-per-file rule): `config/backup.yml` (8 keys, no
packaged seed resource — generated from `BackupConfig`'s `@ConfigEntry` defaults on first boot).
This row confirms every key is present at its `FEATURES.md`-documented default, then flips one or
more representative keys and observes the behaviour follow — **except
`auto_backup.interval`**, which `FEATURES.md` documents as having no observable effect
(`UltiKits/UltiBackup#13` is unrelated; the interval's own dead-key status has no filed issue of
its own beyond being noted directly in `FEATURES.md`, since it is a single, already-obvious
one-line divergence rather than a multi-symptom defect needing its own tracking issue) — this
section deliberately does NOT attempt to exercise it for a cadence change.

| ID | Preconditions | Steps | Expected | Layer | Covers |
|---|---|---|---|---|---|
| ultibackup.config.backup-yml | A fresh `config/backup.yml`, generated by letting the module boot once with no prior file present (no hand-edited copy) | Load the file; confirm all 8 keys listed under `FEATURES.md`'s `## Configuration` section are present at their documented defaults; then, in separate configurations so no toggle masks another's effect, RESTARTING THE SERVER after each edit and before observing its effect — `UltiBackup#reloadSelf()` never calls `super.reloadSelf()` (`UltiKits/UltiBackup#14`), so `/ul reload` alone does NOT pick up a `backup.yml` edit; only a full restart re-reads `BackupConfig` from disk: (a) set `auto_backup.enabled: false` (default true), RESTART, wait one scheduled interval, and confirm `ultibackup.task.auto-backup-all` does NOT fire; (b) set `backup_armor: false` (default true), RESTART, create a backup for a player wearing armor, and confirm the preview GUI's Armor tab (`ultibackup.gui.backup-preview`) shows empty rather than the worn armor; (c) set `max_backups_per_player: 2` (default 10), RESTART, and create 3 backups for the same player in sequence, confirming the oldest is pruned (both its `backup_metadata` row and its content file) immediately after the 3rd is created. Do NOT vary `auto_backup.interval` expecting a cadence change — it has no effect (see `FEATURES.md`'s own note in `## Configuration`) | All 8 keys present at their documented defaults before any change; (a) no `AUTO`-reason backup is created for the disabled interval; (b) the previewed armor tab is empty despite the player wearing armor at backup time; (c) exactly 2 backups remain for the player immediately after the 3rd is created, and the oldest of the original set is gone from both the database and disk | server | |
