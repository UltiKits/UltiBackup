# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

UltiBackup is a UltiTools-API plugin module that provides player inventory backup and restore for Minecraft servers. It supports automatic backups (timed, death, quit), manual backups, GUI management, and SHA-256 data integrity verification. Bilingual (Chinese/English).

**Requires**: UltiTools-API 6.2.0+, Java 8, Spigot API 1.20.1

## Build

```bash
mvn clean package    # Build → target/UltiBackup-1.0.0.jar
```

No tests exist yet. No shading — all dependencies are `provided` scope (resolved by UltiTools-API at runtime).

## Architecture

### Cold/Hot Data Separation

The central design pattern: metadata (hot) lives in the database via UltiTools `DataOperator`, while actual inventory content (cold) lives in YAML files on disk.

- **`BackupMetadata`** (`@Table("backup_metadata")`, extends `BaseDataEntity<String>`) — player UUID, timestamps, file paths, checksums, location info. Stored in DB (MySQL/SQLite/JSON depending on server config). Has an `onDelete()` lifecycle hook that also deletes the associated cold data file.
- **`BackupContent`** (plain POJO) — serialized `ItemStack[]` arrays as YAML strings. Written to `backups/{uuid}_{timestamp}.yml` with a SHA-256 checksum header. Uses Bukkit's `YamlConfiguration` for item serialization/deserialization.

### Data Flow

`BackupService.createBackup()`:
1. `BackupMetadata.fromPlayer()` captures metadata snapshot
2. `BackupContent.fromPlayer()` serializes inventory/armor/enderchest/exp
3. Content saved to YAML file, SHA-256 checksum returned
4. Metadata saved to DB with checksum
5. Old backups pruned per `maxBackupsPerPlayer` config

`BackupService.restoreBackup()`:
1. Verify SHA-256 checksum (content portion only, skipping header comments)
2. If checksum fails → return `CHECKSUM_FAILED` (caller can offer force restore)
3. Load YAML, deserialize items, apply to player

### Key Classes

- **`BackupService`** (`@Service`) — all backup/restore/delete/verify logic. Injected with `BackupConfig` via `@Autowired`. Starts auto-backup scheduler in `@PostConstruct`.
- **`BackupCommand`** (extends `BaseCommandExecutor`) — commands registered via `@CmdExecutor(alias={"backup","invbackup","bk"})`. Uses `@CmdMapping` for subcommand routing, `@CmdCD` for cooldowns, `@RunAsync` for async execution.
- **`BackupListener`** (`@EventListener`) — handles death/quit auto-backups and all GUI click interactions (BackupGUI, BackupPreviewGUI, ForceRestoreConfirmPage).
- **`BackupGUI`** (implements `InventoryHolder`) — paginated chest GUI. Click detection happens in `BackupListener`, not in the GUI class itself.
- **`ForceRestoreConfirmPage`** (extends `BaseConfirmationPage` from `mc.obliviate.inventory`) — the only GUI using the ObliviateInv framework; others use raw `InventoryHolder`.

### GUI Click Handling

All GUI click logic is centralized in `BackupListener.onBackupGUIClick()` and `onPreviewGUIClick()`, dispatching based on `instanceof` checks on `InventoryHolder`. The GUIs themselves only manage display state. Slot layout:
- Slots 0-44: backup items (45 per page)
- Slot 45: previous page / inventory tab
- Slot 47: create button / enderchest tab
- Slot 49: page indicator / info panel
- Slot 53: next page / close button

### UltiTools-API Patterns Used

- `@UltiToolsModule(scanBasePackages={...})` on main class for component scanning
- `@Service` + `@Autowired` for DI (container-managed beans)
- `@PostConstruct` for initialization after injection
- `DataOperator<T>` with `WhereCondition` for database queries
- `@ConfigEntity` + `@ConfigEntry` for typed YAML configuration
- `@CmdExecutor` + `@CmdMapping` + `@CmdParam` + `@CmdSender` for declarative commands
- `@EventListener` on listener classes (not just Bukkit's `Listener` — the UltiTools annotation is required)
- `XVersionUtils.getColoredPlaneGlass(Colors.X)` for cross-version material compatibility
- `plugin.i18n("key")` for internationalization (lang files in `src/main/resources/lang/`)

## Code Conventions

- Java 8 — no `var`, no records, diamond operator OK
- Lombok: `@Data`, `@Builder`, `@Getter/@Setter`, `@NoArgsConstructor/@AllArgsConstructor`
- Bilingual Javadoc (English paragraph, then `<p>`, then Chinese paragraph)
- i18n keys follow `backup.{section}.{name}` pattern with `{PLACEHOLDER}` substitution
- Backup reasons are string constants: `MANUAL`, `AUTO`, `DEATH`, `QUIT`, `ADMIN`

## Gotchas

- `BackupContent.verifyChecksum()` skips lines starting with `#` to extract only the YAML body for checksumming — any change to the header format can break verification
- The auto-backup scheduler callback (`autoBackupAll`) runs async but switches back to sync with `runTask()` because `createBackup()` reads player inventory (must be on main thread)
- `BackupCommand` constructor takes `BackupService` as a parameter — it's not `@Autowired` field injection, so check how UltiTools resolves this (likely via `BaseCommandExecutor` constructor injection)
- `ForceRestoreConfirmPage` uses `ObliviateInv` framework (`BaseConfirmationPage`), while the other GUIs use raw `InventoryHolder` — mixing two GUI frameworks
- `BackupMetadata.onDelete()` deletes the cold data file; if you delete metadata directly via `DataOperator` without calling `onDelete()`, orphan files remain


<claude-mem-context>

</claude-mem-context>