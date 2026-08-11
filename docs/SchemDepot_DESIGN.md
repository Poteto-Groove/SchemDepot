# SchemDepot — Technical Design Specification

> **Status:** Implementation-ready draft  
> **Target:** MVP / v1.0.0  
> **Last verified:** 2026-08-11  
> **Primary implementation agent:** Claude Code  
> **Project type:** Paper/Purpur plugin, designed as a FastAsyncWorldEdit add-on

---

## 0. Executive Summary

SchemDepot is a lightweight collaborative asset registry built on top of FastAsyncWorldEdit (FAWE).

Its purpose is **not** to replace WorldEdit/FAWE schematic handling. FAWE remains responsible for clipboard manipulation, schematic serialization semantics, paste execution, and edit history. SchemDepot adds a small server-level asset-management layer around those capabilities.

The core UX is:

```text
//copy
/sd add OakTree
```

Later, by the same or another authorized builder:

```text
/sd OakTree
```

This immediately pastes the registered asset at the caller's current position.

Builders can inspect the shared library:

```text
/sd list
/sd info OakTree
```

The registry records who registered an asset, when it was registered, its dimensions, and the backing schematic file.

### Core product statement

> Turn a FAWE clipboard into a reusable server asset, track who registered it, browse the shared asset library, and paste any asset with one short command.

---

# 1. Goals

## 1.1 Primary goals

The MVP MUST provide:

1. Registration of the player's current WorldEdit/FAWE clipboard as a named server asset.
2. Direct paste of a registered asset using a short command.
3. Shared server-wide asset discovery.
4. Registration metadata, especially author identity.
5. Persistent storage across server restarts.
6. Permission-aware modification/removal.
7. Compatibility with normal WorldEdit/FAWE undo history where technically supported by the public API.
8. No reimplementation of FAWE's block placement engine.
9. No dependency on FAWE internal implementation classes unless no public API alternative exists.
10. Safe handling of duplicate names and failed storage operations.

---

# 2. Non-goals for v1.0

The following MUST NOT be implemented in the MVP unless required to satisfy a core acceptance criterion:

- Inventory GUI.
- Web UI.
- REST API.
- MySQL/PostgreSQL.
- Cross-server synchronization.
- Cloud schematic storage.
- Categories.
- Tags.
- Ratings/favorites/download counters.
- Asset previews or rendered images.
- Automatic rotation variants.
- Complex search syntax.
- Asset version history.
- Full audit-log database.
- Public marketplace functionality.
- WorldGuard/PlotSquared custom integration beyond what FAWE/WorldEdit already applies naturally.
- Folia support.
- Proxy-side functionality.
- Custom schematic format.
- Direct NBT manipulation.
- NMS usage.
- Reimplementation of copy/paste.
- `/asset` command ownership, because FAWE already uses the asset concept/command.
- Writing SchemDepot metadata into the `.schem` NBT structure.

These are candidates for later releases, not v1.0 requirements.

---

# 3. Naming

## 3.1 Plugin name

**SchemDepot**

Rationale:

- "Schem" immediately communicates schematic/WorldEdit context.
- "Depot" communicates a shared storage/registry.
- Short enough to be memorable.
- Does not claim to be a replacement for WorldEdit or FAWE.

## 3.2 Commands

Primary root:

```text
/schemdepot
```

Primary alias:

```text
/sd
```

The alias is expected to be the normal builder-facing interface.

Do NOT claim `/asset`.

---

# 4. Supported Environment

## 4.1 Runtime baseline

MVP baseline:

- Java: **25**
- Server: **Paper 26.1.2+ / Purpur equivalent**
- FAWE: **2.15.3**
- Minecraft compatibility target:
  - Must test: 26.1.2
  - Must test: 26.2
- Build system: **Gradle Kotlin DSL**

The code should target the lowest API level needed for 26.1.2 and avoid 26.2-only APIs unless absolutely necessary.

## 4.2 Dependency strategy

Prefer public APIs:

1. Paper/Bukkit API.
2. WorldEdit public API (`com.sk89q.worldedit.*`).
3. FAWE only as the required runtime implementation.

Avoid direct imports from:

```text
com.fastasyncworldedit.*
```

unless a public WorldEdit API cannot satisfy a hard requirement.

If direct FAWE API usage becomes necessary, document the exact reason in code and in `README.md`.

## 4.3 Plugin dependency

SchemDepot is an FAWE add-on.

Runtime should fail clearly if FAWE/WorldEdit functionality is unavailable.

Use a hard plugin dependency appropriate to the final plugin descriptor so SchemDepot loads after FAWE.

---

# 5. Core UX

## 5.1 Register asset

Builder flow:

```text
//copy
/sd add OakTree
```

Expected result:

```text
[SchemDepot] Registered "OakTree".
Author: warasugi
Size: 13 x 18 x 12
```

Important:

`/sd add` MUST use the clipboard already stored in the player's WorldEdit `LocalSession`.

It MUST NOT:

- require another region selection,
- run another implicit copy,
- mutate the selected region,
- replace the user's clipboard,
- close a clipboard owned by the player's WorldEdit session.

If the clipboard is empty, return a clear error:

```text
[SchemDepot] Your WorldEdit clipboard is empty. Use //copy first.
```

## 5.2 Paste asset

Normal use:

```text
/sd OakTree
```

Equivalent explicit form:

```text
/sd paste OakTree
```

Expected behavior:

- Resolve `OakTree` case-insensitively.
- Load its SchemDepot-owned `.schem`.
- Paste it relative to the player's current block position.
- Preserve the stored clipboard origin/offset behavior as WorldEdit normally does.
- Do not replace or modify the caller's current WorldEdit clipboard.
- Attempt to register the resulting edit into the caller's WorldEdit history so `//undo` behaves naturally.

Expected result:

```text
[SchemDepot] Pasted "OakTree".
```

## 5.3 List assets

```text
/sd list
/sd list 2
```

Example output:

```text
SchemDepot — Assets 1/3

OakTree          warasugi      13x18x12
ModernLamp       BuilderA      3x7x3
StoneBench       BuilderB      5x3x2
```

Preferred presentation:

- Adventure Components.
- Asset name is clickable and suggests or runs `/sd <name>`.
- Hover text may show author, created date, and dimensions.
- Keep chat output compact.
- Default page size: configurable, default 8.

## 5.4 Asset information

```text
/sd info OakTree
```

Output fields:

```text
Name: OakTree
Author: warasugi
Created: 2026-08-11 21:00
Size: 13 x 18 x 12
```

Do not expose internal filesystem paths or UUIDs to normal users unless debug/admin functionality is explicitly added later.

## 5.5 Rename

```text
/sd rename OakTree LargeOakTree
```

Requirements:

- Rename registry metadata only.
- Backing schematic filename MUST NOT depend on the user-facing asset name.
- Do not move/rename the `.schem` file during a normal rename.
- Enforce duplicate-name rules.
- Enforce ownership/admin permissions.

## 5.6 Remove

```text
/sd remove OakTree
```

Requirements:

- Enforce ownership/admin permissions.
- Remove the registry record.
- Remove or archive the backing schematic safely.
- Log who removed the asset.
- If physical file deletion fails after registry deletion, log the orphan file clearly rather than corrupting other entries.

---

# 6. Command Specification

## 6.1 Command tree

```text
/sd
/sd help

/sd add <name>

/sd paste <name>
/sd <name>

/sd list [page]
/sd info <name>

/sd rename <name> <new-name>
/sd remove <name>
```

`/sd <name>` is a shortcut for `/sd paste <name>`.

## 6.2 Reserved asset names

Because `/sd <name>` doubles as a command shortcut, the following names are reserved:

```text
help
add
paste
list
info
rename
remove
reload
version
admin
```

Comparison MUST be case-insensitive.

Keep this set centralized in one class/constant.

## 6.3 Asset name validation

MVP names:

- 1–64 characters.
- ASCII letters.
- Digits.
- `_`
- `-`
- `.`
- No spaces.
- Case-preserving for display.
- Case-insensitive for lookup and uniqueness.

Recommended validation regex:

```regex
^[A-Za-z0-9][A-Za-z0-9._-]{0,63}$
```

Canonical lookup key:

```text
name.toLowerCase(Locale.ROOT)
```

Examples:

Valid:

```text
OakTree
oak_tree
city.lamp-01
House2
```

Invalid:

```text
Oak Tree
../tree
/tree
tree*
```

`Tree` and `tree` MUST refer to the same logical name and MUST NOT coexist.

---

# 7. Permissions

Recommended nodes:

```text
schemdepot.use
schemdepot.paste
schemdepot.list
schemdepot.info
schemdepot.add

schemdepot.rename.own
schemdepot.rename.any

schemdepot.remove.own
schemdepot.remove.any

schemdepot.admin
```

Suggested behavior:

- `schemdepot.use` can be a parent/common permission.
- `schemdepot.admin` grants all SchemDepot permissions.
- `rename.own` and `remove.own` compare UUIDs, never names.
- Console cannot perform player-dependent clipboard/paste operations.
- Console may use list/info and future administrative operations.

Do not use player names as the authority for ownership.

---

# 8. Data Model

## 8.1 Storage model

Use:

- SQLite for registry metadata.
- `.schem` files for asset data.

Directory layout:

```text
plugins/SchemDepot/
├── config.yml
├── assets.db
├── schematics/
│   ├── <asset-uuid>.schem
│   └── ...
├── tmp/
│   └── ...
└── trash/
    └── ...
```

Human-facing asset names MUST NOT be used as backing filenames.

Reason:

- Rename becomes metadata-only.
- Avoid path traversal issues.
- Avoid filesystem case-sensitivity differences.
- Avoid collisions caused by display-name normalization.
- Keep storage identifiers immutable.

## 8.2 SQLite schema

Use SQLite schema migrations via `PRAGMA user_version`.

Initial schema:

```sql
CREATE TABLE assets (
    id              TEXT PRIMARY KEY,
    name            TEXT NOT NULL,
    normalized_name TEXT NOT NULL UNIQUE,

    author_uuid     TEXT NOT NULL,
    author_name     TEXT NOT NULL,

    created_at      INTEGER NOT NULL,
    updated_at      INTEGER NOT NULL,

    size_x          INTEGER NOT NULL,
    size_y          INTEGER NOT NULL,
    size_z          INTEGER NOT NULL,

    schematic_file  TEXT NOT NULL UNIQUE
);

CREATE INDEX idx_assets_author_uuid
    ON assets(author_uuid);

CREATE INDEX idx_assets_created_at
    ON assets(created_at);
```

Notes:

- `id` is a randomly generated UUID string.
- `schematic_file` should normally be `<id>.schem`.
- `created_at` and `updated_at` use Unix epoch milliseconds in UTC.
- `author_name` is a snapshot for display.
- `author_uuid` is authoritative.
- Do not couple asset identity to Minecraft username.

## 8.3 Domain model

Suggested immutable model:

```java
public record Asset(
    UUID id,
    String name,
    String normalizedName,
    UUID authorUuid,
    String authorName,
    Instant createdAt,
    Instant updatedAt,
    int sizeX,
    int sizeY,
    int sizeZ,
    String schematicFile
) {}
```

Do not expose database concerns from the domain object.

---

# 9. Schematic Format

Use the WorldEdit public clipboard API.

Preferred writer:

```text
BuiltInClipboardFormat.SPONGE_SCHEMATIC
```

Do not manually serialize NBT.

Do not implement the Sponge Schematic specification manually.

Do not write custom SchemDepot metadata inside the schematic.

Reasons:

- SchemDepot metadata belongs to the registry.
- FAWE/WorldEdit should own schematic compatibility.
- Registry metadata must remain queryable without parsing every schematic.
- It avoids coupling SchemDepot to schematic metadata API details.

---

# 10. WorldEdit / FAWE Integration

## 10.1 Get player's clipboard

Conceptual flow:

```java
Player actor = BukkitAdapter.adapt(bukkitPlayer);
SessionManager manager = WorldEdit.getInstance().getSessionManager();
LocalSession session = manager.get(actor);

ClipboardHolder holder = session.getClipboard();
Clipboard clipboard = holder.getClipboard();
```

Handle the WorldEdit empty-clipboard exception explicitly.

Important ownership rule:

> A clipboard obtained from the player's `LocalSession` is owned by WorldEdit/the session. SchemDepot MUST NOT close or mutate it during `/sd add`.

## 10.2 Save clipboard

Conceptual flow:

```java
try (ClipboardWriter writer =
         BuiltInClipboardFormat.SPONGE_SCHEMATIC.getWriter(outputStream)) {
    writer.write(clipboard);
}
```

Actual implementation should use buffered streams and the final current WorldEdit API.

## 10.3 Load schematic

Conceptual flow:

```java
ClipboardFormat format = ClipboardFormats.findByFile(file);

try (ClipboardReader reader = format.getReader(inputStream)) {
    Clipboard clipboard = reader.read();
}
```

If format detection fails:

- Do not paste.
- Log asset ID/name and path internally.
- Tell the user the asset file is invalid/corrupt.

Plugin-owned loaded clipboards should be closed/released if the concrete current API requires it.

## 10.4 Paste

Conceptual flow:

```java
try (EditSession editSession =
         WorldEdit.getInstance().newEditSession(world)) {

    Operation operation = new ClipboardHolder(clipboard)
        .createPaste(editSession)
        .to(target)
        .build();

    Operations.complete(operation);

    localSession.remember(editSession);
}
```

The exact lifecycle/order MUST be checked against the current WorldEdit/FAWE API while implementing.

Functional requirements are more important than reproducing this pseudocode literally:

- Use WorldEdit/FAWE public API.
- Do not manually iterate blocks.
- Do not call Bukkit `Block#setType` in a loop.
- Preserve WorldEdit clipboard offset semantics.
- Register the completed edit in WorldEdit history where supported.
- Verify `/sd <asset>` followed by `//undo` in integration testing.

## 10.5 Do not replace user's clipboard during paste

`/sd <name>` MUST paste from a plugin-loaded clipboard directly.

Do NOT do:

```text
load asset into LocalSession clipboard
then execute //paste
```

That would destroy/replace the builder's existing clipboard and is unacceptable UX.

---

# 11. Paste Semantics

MVP defaults should be conservative and predictable.

Configuration:

```yaml
paste:
  ignore-air-by-default: false
  include-entities: false
  include-biomes: false
```

Default `ignore-air-by-default: false` intentionally follows normal WorldEdit-style full paste semantics more closely.

Do not add user-facing paste flags in the first implementation unless trivial.

Potential future flags:

```text
-a / --ignore-air
-e / --entities
-b / --biomes
-r / --rotation
```

These are explicitly post-MVP unless implementation is nearly free.

---

# 12. Threading Model

## 12.1 Rule

Never perform normal Bukkit world/entity/player mutation from an asynchronous thread.

SchemDepot should separate:

### Main server thread

Allowed/responsible for:

- Bukkit command entry.
- Permission checks.
- Reading player identity.
- Reading/snapshotting Bukkit player location.
- Adapting Bukkit objects to WorldEdit representations where appropriate.
- Scheduling final user-facing responses if necessary.

### Plugin-owned worker executor

Responsible for:

- SQLite operations.
- Filesystem operations.
- Schematic read/write I/O.
- WorldEdit/FAWE operations that the current FAWE public API explicitly supports asynchronously.

Do not call Bukkit world block APIs from this executor.

## 12.2 Executor

Use a small bounded plugin-owned executor.

Suggested:

```text
2 worker threads
named: SchemDepot-Worker-%d
```

Avoid an unbounded cached pool.

Avoid performing large schematic disk writes on the main server thread.

Shut the executor down cleanly during plugin disable.

## 12.3 FAWE async behavior

FAWE maintains compatibility with the WorldEdit API and is designed to support asynchronous WorldEdit operations.

Still:

- Keep Bukkit API calls out of worker threads.
- Treat FAWE public API behavior as the source of truth.
- Do not depend on undocumented internal scheduling classes.

---

# 13. Storage Transaction Semantics

## 13.1 Add asset

Required sequence:

1. Validate player/permission.
2. Validate asset name.
3. Resolve current WorldEdit LocalSession clipboard.
4. Reject empty clipboard.
5. Calculate dimensions.
6. Generate immutable asset UUID.
7. Check duplicate normalized name.
8. Write schematic to a temporary file:
   ```text
   tmp/<uuid>.schem.tmp
   ```
9. Close writer successfully.
10. Move temp file into:
   ```text
   schematics/<uuid>.schem
   ```
11. Insert metadata into SQLite transaction.
12. Send success message.

If database insertion fails after file move:

- Delete the newly created schematic if possible.
- Log cleanup failure if the file cannot be deleted.
- Do not report success.

If schematic write fails:

- Remove temp file if possible.
- Do not insert database metadata.

## 13.2 Atomic filesystem move

Prefer:

```text
ATOMIC_MOVE
```

when supported.

Fallback to a normal move when the filesystem does not support atomic moves.

Never overwrite another asset's file.

## 13.3 Rename asset

Transaction:

1. Resolve existing asset.
2. Verify ownership/permission.
3. Validate new name.
4. Update `name`, `normalized_name`, `updated_at`.
5. Do not touch schematic file.

## 13.4 Remove asset

Safe sequence:

1. Resolve asset.
2. Verify ownership/permission.
3. Move backing file to `trash/<uuid>.schem` when possible.
4. Delete registry row in a transaction.
5. Delete trash file after successful DB operation.
6. Log any orphan/trash cleanup failure.

This does not require a user-facing restore command in v1.0.

---

# 14. Repository Architecture

Suggested package architecture:

```text
<org-package>.schemdepot
├── SchemDepotPlugin.java
│
├── command/
│   ├── SchemDepotCommand.java
│   ├── CommandParser.java
│   └── SchemDepotTabCompleter.java
│
├── asset/
│   ├── Asset.java
│   ├── AssetName.java
│   ├── AssetService.java
│   └── AssetRepository.java
│
├── storage/
│   ├── SqliteAssetRepository.java
│   ├── Database.java
│   ├── DatabaseMigration.java
│   └── SchematicStorage.java
│
├── worldedit/
│   ├── WorldEditFacade.java
│   ├── ClipboardService.java
│   └── PasteService.java
│
├── permission/
│   └── Permissions.java
│
├── message/
│   └── Messages.java
│
└── config/
    └── SchemDepotConfig.java
```

The exact package root should match the GitHub organization/project namespace before the first production commit.

Do not use an example package root in released builds.

---

# 15. Responsibilities by Layer

## 15.1 Command layer

Responsibilities:

- Parse command arguments.
- Validate sender type.
- Check permission.
- Call `AssetService`.
- Render success/error results.
- Perform tab completion.

Must NOT:

- contain SQL,
- write files,
- manually call low-level WorldEdit operations,
- know SQLite schema details.

## 15.2 AssetService

Application/use-case layer.

Responsibilities:

- add,
- paste,
- list,
- info,
- rename,
- remove,
- ownership rules,
- coordinate repository and schematic storage.

Should be unit-testable using fake repository/storage abstractions.

## 15.3 AssetRepository

Interface:

```java
interface AssetRepository {
    Optional<Asset> findByName(String normalizedName);
    Optional<Asset> findById(UUID id);

    List<Asset> list(int limit, int offset);
    long count();

    boolean existsByName(String normalizedName);

    void insert(Asset asset);
    void updateName(UUID id, String name, String normalizedName, Instant updatedAt);
    void delete(UUID id);
}
```

Add transaction-aware methods as needed rather than leaking raw JDBC connections upward.

## 15.4 SchematicStorage

Responsibilities:

- path generation,
- temp path generation,
- save clipboard,
- load clipboard,
- move to trash,
- cleanup.

Must reject path traversal by design.

Only immutable UUID-based generated paths should be accepted by low-level storage code.

## 15.5 WorldEditFacade

Centralize WorldEdit integration so the rest of SchemDepot does not depend on WorldEdit details.

Responsibilities:

- get player LocalSession,
- get current clipboard,
- convert target location,
- read/write clipboard via public API,
- paste,
- register undo history.

This boundary is important because WorldEdit/FAWE API changes should ideally affect one package.

---

# 16. Configuration

Initial `config.yml`:

```yaml
storage:
  database-file: "assets.db"
  schematics-directory: "schematics"
  temp-directory: "tmp"
  trash-directory: "trash"

list:
  page-size: 8

paste:
  ignore-air-by-default: false
  include-entities: false
  include-biomes: false

limits:
  # 0 = use FAWE/server limits only
  max-volume: 0
```

Validate configuration during enable.

Invalid values should be normalized or cause a clear startup error rather than silent undefined behavior.

---

# 17. User-facing Messages

Use Adventure Components.

Do not use legacy section-sign color codes directly in business logic.

Suggested visual prefix:

```text
[SchemDepot]
```

Messages should remain concise.

Examples:

Success:

```text
[SchemDepot] Registered "OakTree".
[SchemDepot] Pasted "OakTree".
[SchemDepot] Renamed "OakTree" to "LargeOakTree".
[SchemDepot] Removed "OakTree".
```

Errors:

```text
[SchemDepot] Your WorldEdit clipboard is empty. Use //copy first.
[SchemDepot] Asset "OakTree" already exists.
[SchemDepot] Asset "OakTree" was not found.
[SchemDepot] "list" is a reserved asset name.
[SchemDepot] You do not have permission to remove this asset.
[SchemDepot] The asset file is missing or corrupted. Contact an administrator.
```

Do not expose raw exceptions to players.

Log exceptions server-side with enough context to diagnose:

- operation,
- asset UUID,
- asset name,
- player UUID when relevant.

---

# 18. Tab Completion

Required:

```text
/sd <TAB>
```

Should suggest:

```text
add
paste
list
info
rename
remove
help
<asset names>
```

For:

```text
/sd paste <TAB>
/sd info <TAB>
/sd rename <TAB>
/sd remove <TAB>
```

suggest matching asset names.

For `/sd remove` and `/sd rename`, optionally filter suggestions by assets the player can modify.

Do not perform blocking SQLite queries synchronously for every keystroke.

Recommended approach:

- maintain a lightweight in-memory asset-name index,
- refresh after add/rename/remove,
- use DB as source of truth.

---

# 19. In-memory Index

Maintain only lightweight metadata needed for fast resolution/tab completion.

Recommended:

```text
ConcurrentHashMap<String, Asset>
```

keyed by normalized name.

The SQLite database remains the persistent source of truth.

Startup:

1. Initialize DB.
2. Load asset metadata into memory.
3. Verify backing file existence.
4. Warn for missing files.

Mutations:

- persist first,
- then update cache after successful storage transaction.

Do not cache full `Clipboard` objects in v1.0.

Reasons:

- potentially huge memory footprint,
- lifecycle complexity,
- stale data risk,
- FAWE already optimizes schematic handling.

---

# 20. Failure Handling

## 20.1 Duplicate name

Database unique constraint is the final authority.

Even if two builders register the same name concurrently:

- exactly one succeeds,
- the other gets a duplicate-name error,
- no orphan schematic should remain after cleanup.

## 20.2 Missing schematic file

If DB row exists but file is missing:

- do not paste,
- return a controlled error,
- log exact asset ID/path,
- keep server running.

## 20.3 Corrupt schematic

If loading fails:

- do not begin paste,
- return controlled error,
- log exception,
- do not modify player's clipboard.

## 20.4 Database unavailable

If DB initialization fails at startup:

- disable SchemDepot,
- log a clear fatal message,
- do not run in a partially initialized state.

## 20.5 FAWE unavailable

Because FAWE is a required runtime dependency:

- SchemDepot should not enable without it.
- Log the missing dependency clearly.

---

# 21. Security Requirements

1. Never derive a filesystem path directly from user asset names.
2. Never concatenate unchecked user input into SQL.
3. Use `PreparedStatement` for every SQL operation.
4. Enforce ownership with UUIDs.
5. Normalize names with `Locale.ROOT`.
6. Reject path separators and unsupported characters via the name regex.
7. Never deserialize arbitrary Java objects.
8. Do not expose raw server filesystem paths to players.
9. Do not use reflection/NMS for core functionality.
10. Validate that the resolved backing schematic lives inside SchemDepot's schematic directory.
11. Do not trust display usernames as stable identity.

---

# 22. Performance Requirements

SchemDepot should add negligible overhead compared with the FAWE operation itself.

Requirements:

- No block-by-block Bukkit paste loop.
- No full asset-file scan per `/sd list`.
- No full schematic parse for list/info.
- No synchronous large schematic write on the server thread.
- No permanent in-memory clipboard cache.
- Pagination uses metadata only.
- Name lookup is O(1) using normalized in-memory index in normal operation.
- Database queries use indexes for name/author/date where relevant.

---

# 23. Undo Behavior

This is an important acceptance requirement.

After:

```text
/sd OakTree
```

the builder should be able to use:

```text
//undo
```

and revert the SchemDepot paste whenever the current public WorldEdit/FAWE API supports normal edit-session history registration.

Implementation approach:

- paste through a normal WorldEdit `EditSession`,
- register the completed `EditSession` in the caller's `LocalSession` history.

Do not implement a separate SchemDepot undo system in v1.0.

If integration testing reveals a lifecycle/API detail that prevents reliable WorldEdit history registration, document it explicitly before changing this requirement.

---

# 24. Compatibility Principles

## 24.1 FAWE

SchemDepot is optimized for FAWE.

Do not detect FAWE and switch to a custom faster implementation.

The WorldEdit-compatible API provided by FAWE is the abstraction boundary.

## 24.2 Plain WorldEdit

Plain WorldEdit support is not a v1.0 requirement.

If the implementation happens to work on plain WorldEdit because it uses public APIs, that is acceptable but should not be claimed as officially supported until tested.

## 24.3 Purpur

Purpur should work through Paper API compatibility.

No Purpur-specific API should be required.

## 24.4 Folia

Folia is explicitly unsupported in v1.0.

Do not claim Folia compatibility without implementing and testing region-aware scheduling.

---

# 25. Build and Project Setup

Use:

```text
Gradle Kotlin DSL
Java toolchain 25
JUnit 5
```

Dependencies should generally be:

```text
compileOnly Paper API
compileOnly WorldEdit/FAWE-compatible API
implementation/shaded SQLite JDBC driver
```

Do not bundle Paper, WorldEdit, or FAWE into the plugin jar.

Shade/relocate only libraries that actually need bundling.

Use reproducible dependency versions.

Add:

```text
./gradlew build
./gradlew test
```

as standard quality gates.

---

# 26. Plugin Descriptor

The final descriptor must define:

- name: `SchemDepot`
- main class
- version from build
- Java/API target
- hard dependency on FAWE
- `/schemdepot`
- alias `/sd`
- permissions

Keep command metadata consistent with the implementation.

Do not register `/asset`.

---

# 27. Testing Strategy

## 27.1 Unit tests

Unit test pure/application logic:

### AssetName

- valid names,
- invalid names,
- reserved names,
- case normalization,
- 64-char boundary.

### AssetService

Using fake repository/storage:

- add success,
- duplicate add,
- rename,
- remove own,
- remove denied,
- rename collision,
- missing asset.

### Pagination

- zero assets,
- exactly one page,
- multiple pages,
- invalid page numbers.

### Permission policy

- own vs any,
- UUID comparison.

## 27.2 Repository tests

Use a temporary SQLite database.

Verify:

- schema migration,
- insert/read,
- unique normalized name,
- update,
- delete,
- persistence after connection recreation.

## 27.3 Storage tests

Temporary directory:

- UUID path generation,
- temp file cleanup,
- atomic/fallback move behavior,
- trash handling,
- path containment.

Do not require a running Minecraft server for these tests.

## 27.4 Integration/manual test server

Required before v1.0 release.

Environment matrix:

```text
Paper 26.1.2 + FAWE 2.15.3
Paper 26.2   + FAWE 2.15.3
```

Purpur smoke test strongly recommended.

---

# 28. MVP Acceptance Criteria

The MVP is complete only when all of the following pass.

## AC-01 — Add clipboard

Given:

- player has permission,
- player used `//copy`,
- clipboard contains a structure,

When:

```text
/sd add OakTree
```

Then:

- asset is stored,
- DB row exists,
- `<uuid>.schem` exists,
- author UUID/name are stored,
- dimensions are stored,
- player's clipboard is unchanged.

## AC-02 — Empty clipboard

Given no WorldEdit clipboard:

```text
/sd add OakTree
```

Then:

- no DB record,
- no permanent schematic,
- clear user error.

## AC-03 — Duplicate names

Given `OakTree` exists:

```text
/sd add oaktree
```

Then:

- command fails,
- original asset remains,
- no second DB row,
- no orphan permanent file.

## AC-04 — Direct paste

Given `OakTree` exists:

```text
/sd OakTree
```

Then:

- asset pastes at caller position,
- caller's previous WorldEdit clipboard remains unchanged.

## AC-05 — Explicit paste

```text
/sd paste OakTree
```

must behave equivalently to:

```text
/sd OakTree
```

## AC-06 — Undo

After successful SchemDepot paste:

```text
//undo
```

reverts the paste using normal WorldEdit/FAWE history.

## AC-07 — Shared library

Asset added by Builder A is visible to authorized Builder B:

```text
/sd list
/sd info OakTree
```

The stored author is Builder A.

## AC-08 — Restart persistence

After full server restart:

- asset metadata still exists,
- paste still works,
- list/info still work.

## AC-09 — Rename

```text
/sd rename OakTree LargeOak
```

Then:

- `/sd LargeOak` works,
- `/sd OakTree` does not,
- backing schematic filename is unchanged.

## AC-10 — Ownership

A non-owner with only `.own` permission cannot rename/remove another builder's asset.

An authorized `.any` or admin user can.

## AC-11 — Missing file

If backing schematic is manually removed:

```text
/sd OakTree
```

must:

- fail cleanly,
- not crash command handling,
- not partially paste,
- log useful diagnostics.

## AC-12 — Main-thread safety

Large add operations must not synchronously write the entire schematic file on the main server thread.

## AC-13 — No FAWE internals

A code review must confirm the MVP does not use `com.fastasyncworldedit.*` implementation classes unless there is an explicitly documented exception.

---

# 29. Implementation Order for Claude Code

Claude Code should implement in this order.

## Phase 1 — Skeleton

1. Initialize Gradle Kotlin DSL project.
2. Configure Java 25 toolchain.
3. Add Paper/WorldEdit-compatible compile-only dependencies.
4. Add SQLite JDBC.
5. Add plugin descriptor.
6. Create plugin entry point.
7. Create config and data directories.

Deliverable:

```text
./gradlew build
```

passes and plugin enables with FAWE installed.

## Phase 2 — Domain + database

1. `Asset`.
2. `AssetName`.
3. `AssetRepository`.
4. SQLite implementation.
5. Migration v1.
6. Repository unit tests.

No WorldEdit integration yet.

## Phase 3 — Schematic storage

1. UUID-based path handling.
2. tmp save.
3. atomic move/fallback.
4. cleanup.
5. trash/remove.
6. storage tests.

## Phase 4 — WorldEdit facade

1. access `LocalSession`,
2. access player's clipboard,
3. save clipboard using public format writer,
4. load clipboard,
5. paste through `EditSession`,
6. preserve clipboard,
7. integrate WorldEdit history.

Validate API signatures against the actual dependency version rather than copying pseudocode blindly.

## Phase 5 — Application service

Implement:

```text
add
paste
list
info
rename
remove
```

Ensure transactional cleanup behavior.

## Phase 6 — Commands

Implement:

```text
/sd add
/sd paste
/sd <asset>
/sd list
/sd info
/sd rename
/sd remove
/sd help
```

Then permissions and tab completion.

## Phase 7 — Integration verification

Run a real Paper + FAWE test server.

Specifically verify:

```text
//copy
/sd add Test
/sd Test
//undo
```

Then restart and repeat paste.

## Phase 8 — Documentation/release

Create README with:

- what SchemDepot is,
- requirement for FAWE,
- command table,
- permissions,
- install steps,
- basic examples.

Do not advertise unimplemented roadmap features as current features.

---

# 30. Claude Code Implementation Rules

These are hard constraints.

1. **Do not reimplement WorldEdit/FAWE paste.**
2. **Do not iterate Bukkit blocks manually.**
3. **Do not replace the player's clipboard when pasting an asset.**
4. **Do not close a clipboard owned by the player's LocalSession.**
5. **Do not use asset names as filenames.**
6. **Do not use raw SQL string concatenation.**
7. **Use PreparedStatement.**
8. **Do not use NMS.**
9. **Do not use reflection for normal functionality.**
10. **Avoid FAWE internal APIs.**
11. **Keep Bukkit API access on the server thread unless the specific API is documented thread-safe.**
12. **Move heavy file/database work off the server thread.**
13. **Register SchemDepot paste in WorldEdit history if supported.**
14. **Do not add GUI/Web/API/category/tag features during MVP implementation.**
15. **Do not silently overwrite an existing asset.**
16. **Case-insensitive name uniqueness is required.**
17. **Use UUID for ownership checks.**
18. **Keep all filesystem paths contained in the plugin data directory.**
19. **A failed write must never be reported as success.**
20. **Every public command must fail gracefully.**
21. **Do not swallow exceptions without logging context.**
22. **Do not add a custom `/asset` root command.**
23. **Do not assume pseudocode API signatures; compile against and verify the current API.**
24. **Prefer simple architecture over premature abstractions.**
25. **Run tests/build after each implementation phase.**

---

# 31. Suggested Result/Error Types

Avoid exception-driven expected control flow at the command layer.

Example:

```java
sealed interface AddAssetResult {
    record Success(Asset asset) implements AddAssetResult {}
    record InvalidName(String reason) implements AddAssetResult {}
    record ReservedName(String name) implements AddAssetResult {}
    record EmptyClipboard() implements AddAssetResult {}
    record DuplicateName(String name) implements AddAssetResult {}
    record StorageFailure(UUID operationId) implements AddAssetResult {}
}
```

This is a suggestion, not a requirement.

Do not over-engineer a giant result hierarchy if a smaller clear model is sufficient.

---

# 32. Logging

Use the plugin logger.

Information-level:

```text
Registered asset OakTree (<uuid>) by <player-uuid>
Renamed asset OakTree -> LargeOak by <player-uuid>
Removed asset LargeOak (<uuid>) by <player-uuid>
```

Warning:

```text
Asset <uuid> references missing schematic file.
Failed to clean orphan schematic <path>.
```

Severe:

```text
Failed to initialize SQLite registry.
Failed to migrate database schema.
```

Do not log schematic contents.

---

# 33. Future Roadmap — Explicitly Out of MVP

Potential v1.1+:

```text
/sd search <query>
/sd list --author <player>
/sd category ...
/sd tag ...
/sd update <name>
/sd load <name>
```

Potential paste flags:

```text
/sd paste OakTree -a
/sd paste OakTree -r 90
```

Potential later product features:

- Inventory browser GUI.
- Asset preview thumbnails.
- Asset versioning.
- Audit history.
- Categories/tags.
- Favorites.
- Cross-server database/storage.
- Web UI.
- External API.
- Cloud backing store.
- Import existing FAWE schematic directories.
- Asset export.
- Role/group ownership.
- Approval/moderation workflow.

None should block v1.0.

---

# 34. Final Architecture Decision Record

## ADR-001 — Registry instead of schematic replacement

**Decision:** SchemDepot manages metadata and lifecycle; FAWE/WorldEdit owns clipboard/schematic/paste behavior.

**Reason:** Avoids reimplementing a mature world-editing engine.

## ADR-002 — SQLite + UUID-named `.schem`

**Decision:** Metadata in SQLite, schematic body in `.schem`, immutable UUID filename.

**Reason:** Clean rename semantics, safe paths, future-query flexibility.

## ADR-003 — Metadata not embedded into `.schem`

**Decision:** Author/name/timestamps remain in SQLite.

**Reason:** Avoid format coupling and expensive metadata scanning.

## ADR-004 — `/sd <name>` is direct paste

**Decision:** The short form pastes without changing player clipboard.

**Reason:** This is SchemDepot's primary UX advantage.

## ADR-005 — WorldEdit public API boundary

**Decision:** Prefer `com.sk89q.worldedit.*`; avoid FAWE internals.

**Reason:** FAWE explicitly maintains WorldEdit API compatibility and this minimizes breakage.

## ADR-006 — Case-insensitive unique names

**Decision:** Preserve display casing but normalize with `Locale.ROOT` for lookup/uniqueness.

**Reason:** Minecraft commands are human-entered and case-only duplicates are confusing.

## ADR-007 — Undo through WorldEdit history

**Decision:** Reuse normal LocalSession/EditSession history.

**Reason:** Builders already understand `//undo`; a second undo system would be redundant.

## ADR-008 — No GUI in MVP

**Decision:** Chat command UX first.

**Reason:** Fastest path to a useful plugin and fits existing FAWE builder workflows.

---

# 35. Pre-implementation Verification

Verified before this specification was finalized:

- Current FAWE release checked on 2026-08-11: **2.15.3**.
- FAWE 2.15.3 includes **Minecraft/Paper 26.2 support**.
- FAWE 2.15.1/2.15.2 line includes **26.1.2 support**.
- Current Paper 26.1+ runtime requires **Java 25**.
- WorldEdit public API documents:
  - player `LocalSession`,
  - access to player's clipboard,
  - schematic save/load,
  - clipboard paste,
  - storing an `EditSession` in player history.
- Sponge Schematic specification current published major version remains **v3**.
- FAWE states that it maintains compatibility with the WorldEdit API and supports asynchronous WorldEdit API usage.

Therefore the MVP architecture does **not** require a custom block-paste engine or FAWE internal API.

---

# 36. Final Scope Check

Before writing code, Claude Code should restate these four invariants:

```text
1. SchemDepot is a registry, not a WorldEdit replacement.
2. /sd add saves the existing WorldEdit clipboard.
3. /sd <name> directly pastes without replacing the player's clipboard.
4. Metadata lives in SQLite; structure data lives in UUID-named .schem files.
```

If an implementation choice conflicts with one of these invariants, stop and redesign that part instead of working around the specification.

---

# 37. Definition of Done

v1.0.0 is ready when:

```text
//copy
/sd add TestAsset
/sd list
/sd info TestAsset
/sd TestAsset
//undo
/sd rename TestAsset RenamedAsset
/sd RenamedAsset
/sd remove RenamedAsset
```

all work correctly on the supported FAWE/Paper test matrix, permissions are enforced, persistence survives restart, failures are recoverable/diagnosable, and the plugin does not mutate the user's clipboard during asset paste.

At that point, SchemDepot already provides a coherent standalone value proposition and can be released without GUI, tags, cloud sync, or other expansion features.
