# Nuclear Tech Reborn

Сборка `minecraft-24` для LANMinecraft. Minecraft 1.12.2, Forge 14.23.5.2859, Java 8. Авторский клиент NITRO v1.14.1 от copernicium_282 имеет CurseForge project ID 539213 и file ID 8946536. Поле версии внутри исходного `manifest.json` содержит `v1.14.0`. SHA256 оригинального ZIP `070375a4d93bc1af9765ab5fefa6d8bf56cde5d0d1b548d64d34e3c6f442296c` совпадает у CurseForge и GitHub автора. [CurseForge](https://www.curseforge.com/minecraft/modpacks/nuclear-tech-reborn/files/8946536) · [Выпуск автора](https://github.com/Copernicium282/NITRO/releases/tag/NITRO-1.12.2-v1.14.1)

## Восстановление

Из корня LANMinecraft укажите новый каталог `$stage` внутри принадлежащего задаче стенда с `RUN.json`.

```powershell
python -B tools/PackData/CurseForge/release_overlay.py stage --pack-source LocalSources/Nuclear-Tech-Reborn --output $stage --locked
```

`tools/upstream-client-lock.json` закрепляет исходный ZIP и overrides. `tools/upstream-lock.json` добавляет исходные JAR, размеры, SHA256 и официальные источники. `tools/overlay.json` выключает в Universal Tweaks две функции меню, `LAN Server Properties` и `Toggle Cheats Button`, и устанавливает настройки журналирования для Forge 1.12.2. Остальные настройки автора, включая квесты, оформление и конфигурацию Simple Voice Chat, остаются побайтно сверяемыми.

`tools/network-logging-proof.json` закрепляет проверку Forge 14.23.5.2859 и его Log4j 2.15.0 на Java 8. Сетевые исключения сохраняются со вложенными причинами, а обычный DEBUG-поток пакетов отфильтрован. Атрибуция и условия поставки находятся в [NOTICE.md](NOTICE.md).

## Обновление автора

Снимите `prepare.py --client-only` для нового официального file ID в отдельном каталоге. Сверьте новую квитанцию и выполните `release_overlay.py review-update`. Изменение входных байтов Universal Tweaks требует пересмотра двух точечных замен. После переноса рецепта заново сформируйте полный lock, снимите фактический список загруженных модов и повторите нативную проверку. Выполните `stage_release.py` и `release_overlay.py verify` по [инструкции инструментов](https://github.com/MarkZamore/LANMinecraft/blob/main/tools/PackData/CurseForge/README.md).

На проверке создайте новый мир без команд и откройте его через Steam. Подтвердите возврат в игру одним нажатием, «Жалобы», сообщение с адресом и действиями Steam, запрет команд у хоста и тестового гостя и сохранение мира. Для повторного открытия используйте SHA256 `level.dat` после штатного выхода. Отдельно фиксируйте подключение настоящего второго аккаунта, если оно проверялось. Настройки конкретной сборки находятся здесь, общий Java 8 адаптер Steam остаётся в LANMinecraft.

## Приёмка v1.14.1

[Нативный отчёт](tools/qa-java8-steam.json) фиксирует проверку Java 8 и публичного лаунчера 497, хеши файлов и границы проверки. [Патч проверки](tools/nuclear-tech-reborn-native-witness.patch) добавляет проверку прав JEI, принудительной ошибки публикации и списка серверов. Применяйте его к копии [Forge112SteamQaAgent.java](https://github.com/MarkZamore/LANMinecraft/blob/6b3ad0331934f06395375bfc4923c73de464e3f9/tools/PackData/CurseForge/Forge112SteamQaAgent.java) с исходным SHA256 из отчёта. Компилируйте копию в каталоге временного стенда по разделу о Forge 1.12.2 [инструкции инструментов](https://github.com/MarkZamore/LANMinecraft/blob/7400fab6ff971161922d6014dac22c36d4523b8f/tools/PackData/CurseForge/README.md), передайте хеш итогового архива карточки и исходного `level.dat` второму запуску. Проверочный агент и его мод-помощник используются только в стенде.

[Отчёт о публикации](tools/qa-public-release.json) содержит ревизию каталога, SHA256 манифеста и карточки, результаты полной установки из GitHub и холодного старта публичного EXE. [Выпуск `pack-latest`](https://github.com/MarkZamore/Nuclear-Tech-Reborn/releases/tag/pack-latest)
