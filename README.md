# SchemDepot

FastAsyncWorldEdit (FAWE) のクリップボードを **サーバー共有の名前付きレジストリ** に登録し、`/sd <name>` で誰でも直接ペーストできるようにする Paper プラグインです。

## これは何か / 何ではないか

- SchemDepot は WorldEdit/FAWE の**置き換えではありません**。あくまで FAWE の上に薄い共有レジストリ層を載せるものです。
- `//schem save` / `//schem load` はプレイヤー**個人**のファイル操作（自分の環境にファイルを保存・読込するだけ）ですが、SchemDepot の `/sd add` / `/sd <name>` は**サーバー全員が使える名前付きの共有アセット**を操作します。名前で引けること、誰が登録したか・いつ登録したかを他のプレイヤーも `list`/`info` で確認できることが違いです。
- `/sd <name>` でのペーストは **プレイヤー自身の WorldEdit クリップボードを一切書き換えません**。ペースト後もそれまでのクリップボードがそのまま残るので、`//undo` でペーストだけを取り消せます。

## 動作要件

| 項目 | バージョン |
|---|---|
| Paper / Purpur | 26.1.2 (build 74) 以降 |
| Java | 25 |
| FastAsyncWorldEdit | 2.15.3（**必須**） |

動作確認環境: Paper 26.1.2 (build 74) + FastAsyncWorldEdit 2.15.3 + Java 25。本番想定は Debian 12 上の Purpur 26.1.2 です。

FastAsyncWorldEdit は `paper-plugin.yml` 上で `required: true` かつ `load: BEFORE` の依存として宣言されており、導入されていない・起動順が異なる場合は SchemDepot は正常に動作しません。

## インストール

1. FastAsyncWorldEdit 2.15.3 以上を先に `plugins/` に導入します。
2. `SchemDepot-1.0.0.jar` を `plugins/` に配置します。
3. サーバーを起動します。初回起動時に `plugins/SchemDepot/` 以下が自動生成されます。

```text
plugins/SchemDepot/
├── config.yml       # 設定ファイル
├── assets.db        # アセットのメタデータ (SQLite)
├── schematics/       # 登録済みスキマティクス本体 (<uuid>.schem)
├── tmp/               # 書き込み中の一時ファイル
└── trash/             # 削除時の一時退避先
```

アセットの表示名（プレイヤーが `/sd` で指定する名前）と、裏側のファイル名（`<uuid>.schem`）は分離されています。そのため `/sd rename` で表示名を変えても、`schematics/` 内のファイル名は変わりません。

## 使い方

典型的なフローは次の通りです。

```text
//copy                    # 範囲を選択してコピー（FAWEの通常操作）
/sd add MyBuilding         # クリップボードを "MyBuilding" として登録
/sd MyBuilding              # 誰でも /sd <name> で直接ペースト
//undo                       # ペースト自体は通常のWorldEdit操作として取り消せる
```

`/sd add` した後もプレイヤー自身のクリップボードは変化しません。同様に `/sd <name>` でのペーストも、ペーストした本人・他のプレイヤーいずれのクリップボードも書き換えません。

## コマンド一覧

ルートコマンドは `/schemdepot`、エイリアスは `/sd` です（実サーバーに登録されるラベルは `schemdepot`, `sd`, `schemdepot:schemdepot`, `schemdepot:sd`）。以下は `/sd` 表記で記載します。

| コマンド | 説明 | 必要な権限 | プレイヤー専用 |
|---|---|---|---|
| `/sd`, `/sd help` | ヘルプを表示 | `schemdepot.use` | - |
| `/sd add <name>` | 自分の WorldEdit クリップボードを `<name>` として登録 | `schemdepot.add` | ✓ |
| `/sd paste <name>` | 登録済みアセット `<name>` をペースト | `schemdepot.paste` | ✓ |
| `/sd <name>` | `/sd paste <name>` のショートカット | `schemdepot.paste` | ✓ |
| `/sd list [page]` | 登録済みアセットの一覧を表示 | `schemdepot.list` | - |
| `/sd info <name>` | アセット `<name>` の詳細（作成者・作成日時・サイズ）を表示 | `schemdepot.info` | - |
| `/sd rename <name> <new-name>` | アセットをリネーム | `schemdepot.rename.own`（自分が登録したもの）または `schemdepot.rename.any`（誰のものでも） | - |
| `/sd remove <name>` | アセットを削除 | `schemdepot.remove.own`（自分が登録したもの）または `schemdepot.remove.any`（誰のものでも） | - |

`add` / `paste` / `/sd <name>` はコンソールから実行できません（プレイヤーの位置・クリップボードが必要なため）。`rename`/`remove` をコンソールから実行した場合は常に `.any` 権限が必要になります（コンソールは特定のプレイヤーの所有物にはなり得ないため）。

## 権限一覧

| 権限ノード | 説明 | デフォルト |
|---|---|---|
| `schemdepot.use` | `/schemdepot`（`/sd`）ルートコマンドとヘルプの利用 | `true`（全員） |
| `schemdepot.list` | `/sd list` でアセット一覧を閲覧 | `true`（全員） |
| `schemdepot.info` | `/sd info` でアセット詳細を閲覧 | `true`（全員） |
| `schemdepot.paste` | `/sd <name>` / `/sd paste <name>` でワールドにペースト | `op` |
| `schemdepot.add` | `/sd add` でクリップボードをアセットとして登録 | `op` |
| `schemdepot.rename.own` | 自分が登録したアセットのリネーム | `op` |
| `schemdepot.rename.any` | 誰が登録したアセットでもリネーム | `op` |
| `schemdepot.remove.own` | 自分が登録したアセットの削除 | `op` |
| `schemdepot.remove.any` | 誰が登録したアセットでも削除 | `op` |
| `schemdepot.admin` | 上記すべての権限を一括付与 | `op` |

`schemdepot.list` と `schemdepot.info` は読み取り専用（ファイルパスや内部 UUID は表示しない）なのでデフォルト `true` です。それ以外の操作系ノードはワールドの書き換え・ディスク消費・共有レジストリの変更を伴うため `op` がデフォルトです。

各操作ノードは子として `schemdepot.use` を持つため、例えば `schemdepot.paste` だけを付与すればルートコマンドの `requires(schemdepot.use)` も同時に満たされ、単独付与だけで動作します。

## アセット名のルール

`AssetName.kt` で定義されている規則です。

- 正規表現 `^[A-Za-z0-9][A-Za-z0-9._-]{0,63}$` に一致すること（先頭は英数字、以降 63 文字まで英数字・`.`・`_`・`-`）。パス区切り文字や空白、`..` などは使えません。
- 大文字小文字は区別されません（`Locale.ROOT` で正規化して比較されるため、`OakTree` と `oaktree` は同じ名前として扱われます）。
- 以下は予約語としてアセット名に使用できません: `help`, `add`, `paste`, `list`, `info`, `rename`, `remove`, `reload`, `version`, `admin`

## 設定 (`config.yml`)

| キー | デフォルト | 説明 |
|---|---|---|
| `storage.database-file` | `assets.db` | アセットレジストリ（メタデータ）を保持する SQLite データベースファイル名 |
| `storage.schematics-directory` | `schematics` | 登録済みスキマティクス (`.schem`) の保存ディレクトリ |
| `storage.temp-directory` | `tmp` | 書き込み中の一時ファイルの保存ディレクトリ |
| `storage.trash-directory` | `trash` | 削除時の一時退避先ディレクトリ |
| `list.page-size` | `8` | `/sd list` の1ページあたりの表示件数 |
| `paste.ignore-air-by-default` | `false` | `true` にすると空気ブロックを無視してペーストする（WorldEdit `-a` 相当） |
| `paste.include-entities` | `false` | `true` にするとエンティティも含めてペーストする |
| `paste.include-biomes` | `false` | `true` にするとバイオーム情報も含めてペーストする |
| `limits.max-volume` | `0` | アセット登録時のクリップボード体積（バウンディングボックスのブロック数）上限。`0` は無制限（FAWE/サーバー側の制限にのみ従う） |

`storage.*` の4項目はプラグインのデータフォルダ直下に置く単一のファイル/ディレクトリ名でなければなりません。絶対パスやドライブレター、`/`・`\`・`:` を含む値、`.`・`..` はすべて拒否されます。不正な値は起動時に `WARNING` ログを出したうえでデフォルト値にフォールバックします（設定ファイルの記述ミスがサーバー起動を止めることはありません）。同様に `list.page-size` が1未満、`limits.max-volume` が負の値の場合もデフォルトにフォールバックしつつ `WARNING` を出します。

## 運用上の注意（重要）

> [!WARNING]
> - SchemDepot v1.0 には **アセット数・合計ディスク容量・1件あたりのサイズ・実行レートのいずれにも上限がありません。**
> - `limits.max-volume` の**デフォルトは `0`（無制限）**です。設定した場合もバウンディングボックスの**体積のみ**を見るため、シュルカーボックスや大量の本を詰め込んだ棚など、**体積は小さいのに NBT データが巨大**な構造物は制限できません。
> - したがって **`schemdepot.add` 権限は信頼できるメンバーにのみ付与してください。** 悪意あるユーザーが巨大な範囲を連続で `/sd add` すると、ディスクを使い切りサーバー全体が停止し得ます。
> - これは身内向け建築サーバーでの利用を想定した v1.0 の意図的なスコープです。不特定多数が使う公開サーバーで運用する場合は、別途クォータやレート制限の仕組みを実装・導入してください。

## バックアップ

`plugins/SchemDepot/assets.db`（メタデータ）と `plugins/SchemDepot/schematics/`（構造データ）は対になっています。**両方をセットでバックアップ・復元**してください。片方だけでは復元できません（`assets.db` だけではファイル実体がなく、`schematics/` だけでは名前や作成者などのメタデータが失われます）。

## ソースからのビルド

```sh
./gradlew build
```

成果物は `build/libs/SchemDepot-1.0.0.jar`（Shadow プラグインによる shaded jar、SQLite JDBC ドライバなどの依存を同梱）です。同じディレクトリに `SchemDepot-1.0.0-thin.jar`（依存を含まない素の jar）も生成されますが、こちらは配布・導入対象ではありません。

> [!WARNING]
> サーバーを起動したままビルドすると、実行中の jar がロックされて壊れた成果物になることがあります。ビルド前には必ずサーバーを停止してください。

テストサーバーを立てて動作確認する場合:

```sh
./gradlew runServer
```

Paper 26.1.2 と FastAsyncWorldEdit 2.15.3 を自動でダウンロードして起動します。

## テスト

`./gradlew test` で109件のテストが実行されます（うち1件は実行環境に依存するためスキップされる場合があります）。

## 依存関係

| 依存 | バージョン | スコープ |
|---|---|---|
| Paper API | 26.1.2 (build 74) | `compileOnly` |
| WorldEdit (Bukkit) API | 7.4.5 | `compileOnly`（FAWE がランタイムで提供） |
| SQLite JDBC | 3.53.2.1 | `implementation`（shaded jar に同梱） |
| Kotlin | 2.4.10 | - |

## 設計ドキュメント

コマンド仕様・データモデル・スレッドモデル・障害時の挙動などの詳細は [`docs/SchemDepot_DESIGN.md`](docs/SchemDepot_DESIGN.md) を参照してください。
