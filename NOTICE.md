# Nuclear Tech Reborn v1.14.1

Автор оригинальной сборки — **copernicium_282**. Авторский клиент для Minecraft 1.12.2 закреплён по CurseForge project ID `539213`, file ID `8946536` и GitHub-коммиту `3a0c98739571d6d90927015ea23903907b0f4529`. Оба авторских источника выпуска содержат одинаковый архив размером 48 604 013 байт с SHA256 `070375a4d93bc1af9765ab5fefa6d8bf56cde5d0d1b548d64d34e3c6f442296c`. [Файл CurseForge](https://www.curseforge.com/minecraft/modpacks/nuclear-tech-reborn/files/8946536) · [Выпуск автора](https://github.com/Copernicium282/NITRO/releases/tag/NITRO-1.12.2-v1.14.1) · [Исходники автора](https://github.com/Copernicium282/NITRO/tree/3a0c98739571d6d90927015ea23903907b0f4529)

Авторские материалы сборки опубликованы на условиях GPLv3. Исходники изменений LANMinecraft и порядок пересборки доступны в этом репозитории, список исходных файлов и их SHA256 закреплён в `tools/upstream-lock.json`, а рецепт изменений — в `tools/overlay.json`. [Лицензия оригинальной сборки](https://www.curseforge.com/minecraft/modpacks/nuclear-tech-reborn/license) · [Текст GPLv3](licenses/GPL-3.0.txt) · [Пересборка](README.md)

## Сторонние моды

Владелец публикации подтвердил наличие разрешений на размещение полного комплекта этого выпуска в GitHub Releases LANMinecraft, включая закреплённые файлы JourneyMap, Collective и FTB. Сторонние компоненты сохраняют собственные условия. При обновлении нужно сверить применимость разрешений к новому набору файлов. Размеры, SHA256 и официальные источники закреплены в [lock-файле](tools/upstream-lock.json).

| Компонент | CurseForge project/file ID | Авторский файл |
|---|---|---|
| JourneyMap | `32274/5172461` | [journeymap-1.12.2-5.7.1p3.jar](https://www.curseforge.com/minecraft/mc-mods/journeymap/files/5172461) |
| Collective | `342584/3533131` | [collective-1.12.2-3.0.jar](https://www.curseforge.com/minecraft/mc-mods/collective/files/3533131) |
| FTB Utilities | `237102/3157548` | [FTBUtilities-5.4.1.131.jar](https://www.curseforge.com/minecraft/mc-mods/ftb-utilities-forge/files/3157548) |
| FTB Library | `237167/2985811` | [FTBLib-5.4.7.2.jar](https://www.curseforge.com/minecraft/mc-mods/ftb-library-legacy-forge/files/2985811) |
| FTB Backups | `314904/2819669` | [FTBBackups-1.1.0.1.jar](https://www.curseforge.com/minecraft/mc-mods/ftb-backups-forge/files/2819669) |
| FTB Ultimine | `386134/3078750` | [ftb-ultimine-1202.3.5.jar](https://www.curseforge.com/minecraft/mc-mods/ftb-ultimine-forge/files/3078750) |

Условия опубликованы правообладателями. [JourneyMap](https://teamjm.github.io/journeymap-docs/latest/about/licensing/) · [Collective](https://www.curseforge.com/minecraft/mc-mods/collective/license) · [FTB](https://feed-the-beast.com/raw/docs/mod-license)

## Библиотеки в авторском архиве

Следующие JAR поставляются из `mods/1.12.2` оригинального архива. Исходные уведомления приведены в `licenses/`.

| Компонент | Условия и атрибуция | Источник |
|---|---|---|
| Scala Library, Compiler и Reflect 2.11.1, Scala Actors 2.11.0 | [BSD-3-Clause](licenses/Scala-2.11-LICENSE.md), EPFL и Typesafe, Inc. | [2.11.1](https://github.com/scala/scala/blob/v2.11.1/doc/LICENSE.md) · [2.11.0](https://github.com/scala/scala/blob/v2.11.0/doc/LICENSE.md) |
| ASM в Scala Compiler | [BSD-3-Clause](licenses/Scala-Compiler-ASM-LICENSE.txt) | [Уведомление Scala](https://github.com/scala/scala/blob/v2.11.1/doc/licenses/bsd_asm.txt) |
| Scala XML 1.0.2 и Scala Parser Combinators 1.0.1 | [BSD-3-Clause](licenses/Scala-XML-and-Parser-Combinators-LICENSE.md), EPFL и Typesafe, Inc. | [XML](https://github.com/scala/scala-xml/blob/v1.0.2/LICENSE.md) · [Parser Combinators](https://github.com/scala/scala-parser-combinators/blob/v1.0.1/LICENSE.md) |
| Scala Swing 1.0.1 | [BSD-3-Clause](licenses/Scala-Swing-LICENSE.md), EPFL и Typesafe, Inc. | [Scala Swing](https://github.com/scala/scala-swing/blob/v1.0.1/LICENSE.md) |
| Scala Continuations library и compiler plugin 1.0.2_mc | [BSD-3-Clause](licenses/Scala-Continuations-LICENSE.md), EPFL и Typesafe, Inc. | [Scala Continuations 1.0.2](https://github.com/scala/scala-continuations/blob/v1.0.2/LICENSE.md) · [Дистрибутив Forge](https://files.minecraftforge.net/net/minecraftforge/forge/index_1.12.2.html) |
| Scala Actors Migration 1.1.0 | [BSD-3-Clause](licenses/Scala-2.11-LICENSE.md), © 2005–2011 LAMP/EPFL | [Метаданные 1.1.0](https://repo.maven.apache.org/maven2/org/scala-lang/scala-actors-migration_2.11/1.1.0/scala-actors-migration_2.11-1.1.0.pom) · [Исходники 1.1.0](https://github.com/scala/actors-migration/tree/v1.1.0) |
| Akka Actor 2.3.3 | [Apache-2.0](licenses/Apache-2.0.txt), [© 2009–2014 Typesafe Inc.](licenses/Akka-2.3.3-LICENSE.txt) | [Akka 2.3.3](https://github.com/akka/akka/blob/v2.3.3/LICENSE) |
| Typesafe Config 1.2.1 | [Apache-2.0](licenses/Apache-2.0.txt), © 2011–2012 Typesafe Inc. | [Лицензия Config 1.2.1](https://github.com/lightbend/config/blob/v1.2.1/LICENSE-2.0.txt) · [Исходники 1.2.1](https://github.com/lightbend/config/tree/v1.2.1) |
