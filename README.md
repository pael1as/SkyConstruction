[![CurseForge](https://img.shields.io/badge/CurseForge-SkyConstruction-orange?logo=curseforge&logoColor=white)](https://www.curseforge.com/minecraft/mc-mods/skyconstruction)
[![CurseForge](https://img.shields.io/badge/CurseForge-VoxyForge-orange?logo=curseforge&logoColor=white)](https://www.curseforge.com/minecraft/mc-mods/skyconstruction-voxyforge)
[![Modrinth](https://img.shields.io/badge/Modrinth-SkyConstruction-green?logo=modrinth&logoColor=white)](https://modrinth.com/mod/skyconstruction)
[![Modrinth](https://img.shields.io/badge/Modrinth-VoxyForge-green?logo=modrinth&logoColor=white)](https://modrinth.com/mod/skyconstruction-voxyforge)

# SkyConstruction (NeoForge 1.21.1)

[![NeoForge](https://img.shields.io/badge/NeoForge-21.1.228+-orange.svg)](https://neoforged.net/)
[![Minecraft](https://img.shields.io/badge/Minecraft-1.21.1-brightgreen.svg)](https://www.minecraft.net/)
[![License: MIT](https://img.shields.io/badge/License-MIT-yellow.svg)](LICENSE)

**SkyConstruction** — серверно-клиентский мод для NeoForge 1.21.1, снимающий ванильный лимит высоты мира и позволяющий строить конструкции вплоть до **Y = 1000+** без потери производительности, порчи чанков и рассинхронизации коллизий.

---

## Редакции проекта (Branches)

Проект поддерживается в двух специализированных ветках:

| Ветка | Название | Назначение и зависимости |
|---|---|---|
| `main` | **SkyConstruction (Base)** | Классическая версия. Требует **Sodium 0.6.9+**. Не содержит кода Voxy. |
| `voxy` | **SkyConstruction VoxyForge** | Специализированная сборка с расширенной поддержкой LOD-рендеринга. Требует **Sodium 0.6.9+** и **Voxy 0.1.1+**. |

---

## Техническая архитектура

Снятие лимита высоты в Minecraft 1.21.1 требует согласованного перехвата логики в ядрах сервера, клиента, сетевого стека, рендера и физических движков. Ниже описаны ключевые компоненты реализации:

### 1. Управление высотой и массивами секций
* **`LevelMixin` & `LevelHeightAccessorMixin`**: Динамически модифицируют границы `minBuildHeight`, `maxBuildHeight` и общее число секций `getSectionsCount()` на клиенте и сервере, опираясь на синглтоны зон высот.
* **`ChunkAccessMixin` & `LevelChunkMixin`**: Ванильный движок жестко рассчитан на массивы секций длиной 24 (диапазон $Y \in [-64, 319]$). Для предотвращения `ArrayIndexOutOfBoundsException` поля `sections` перехвачены через `@Shadow @Mutable` без применения низкоуровневого `Unsafe`. При обращении к секциям выше 320 массив динамически и потокобезопасно расширяется до фактической границы (до 68 секций при Y=1024) с заполнением пустых слотов валидными пустыми контейнерами палитр.

### 2. Сериализация, чанки и сетевой стек
* **`ChunkSerializerMixin`**: Ванильный сериализатор сохраняет только первые 24 секции. Мод перехватывает сохранение и запись NBT: расширенные непустые секции ($Y \ge 320$) упаковываются в защищенный композитный тег `SkyForgeSections`. При чтении чанка секции восстанавливаются до передачи чанка в мир.
* **`LevelChunkMixin` (`replaceWithPacketData`)**: Для предотвращения критического `Netty Buffer Underflow` при чтении сетевых пакетов на клиенте установлен `@Redirect` на `LevelChunkSection.read(FriendlyByteBuf)`. Если входящий буфер исчерпан, чтение безопасно завершается без разрыва сетевого соединения.
* **`SkyConstructionSyncPayload`**: Пользовательский сетевой пакет (Clientbound), транслирующий границы активных высотных зон от сервера к клиентам в радиусе видимости.

### 3. Рендеринг (Sodium & Voxy)
* **`SodiumRenderSectionManagerMixin`**: В ванильном Sodium алгоритм `OcclusionCuller` инициирует BFS-обход от корневой секции камеры. При высоте игрока $Y \ge 320$ отсутствие смежных секций в графе приводило к обрыву обхода и полному исчезновению ближних чанков.
  * Мод формирует связную вертикальную колонку: все секции от `minSec` до `maxSec` наполняются легковесными `BuiltSectionInfo.EMPTY`.
  * Достройка колонок графа выполняется лениво с бюджетом **24 колонки за кадр**, что исключает микрофризы и просадки FPS при перемещении и смене дистанции прорисовки.
* **`VoxyRenderSystemMixin`** *(только в ветке `voxy`)*: Расширяет верхний предел `maxSec` октодерева Voxy, позволяя воксельному LOD-пайплайну визуализировать мегаструктуры на экстремальных дистанциях.

### 4. Физика и совместимость с модами
* **`ChunkHolderMixin`**: Динамически масштабирует массив очередей изменений блоков `changedBlocksPerSection`, предотвращая краш сервера при выполнении `/setblock` или установке блоков на $Y \ge 320$.
* **`SablePhysicsMixin` (Create Aeronautics)**: Физический акселератор `LevelAccelerator` кеширует границы высоты в `final`-поля при создании и отсекает все воксели $Y \ge 320$ как `Blocks.AIR`. Миксин переопределяет поля через `@Shadow @Mutable` и перенаправляет опрос вокселей напрямую в расширенные секции `LevelChunk.getBlockState()`, обеспечивая твердотельные коллизии физических кораблей и механизмов Create Aeronautics на высоте до 1000 блоков.
* **`LithiumCollisionSweeperMixin`**: Адаптирует быстрый перебор коллизий `ChunkAwareBlockCollisionSweeper` из мода Lithium под произвольную длину массива секций.

### 5. Совместимость и системные хаки
* **`SkyConstructionMixinPlugin`**: Принудительно инициализирует `Locale.setDefault(Locale.US)` на этапе ранней загрузки миксинов. Это устраняет критический сбой сторонних конфигураторов модов (например, FlyHigher), генерирующих JSON-конфигурации с плавающей точкой через запятую на машинах с русскоязычной локалью ОС.

---

## Игровые команды

Мод включает процедурную команду для операторов сервера (OP ур. 2+):

```mcfunction
/skyforge <radius_in_chunks> <min_y> <max_y>
```

* **`radius_in_chunks`**: Радиус обработки территории вокруг игрока в чанках (без ограничений, `0..N`).
* **`min_y`**: Минимальная высота мира (до `-64`).
* **`max_y`**: Максимальная высота мира (до `1000`).

> **Важно:** Обработка чанков производится процедурно через серверную очередь (`ChunkZoneBatchTask`) по 10 чанков за тик. Сервер не зависает, поток Netty не перегружается, а активные секции вокруг игроков расширяются синхронно до старта процедурного батча.

---

## Зависимости

| Мод | Статус | Примечание |
|---|---|---|
| **NeoForge** | Обязательно | Версия `21.1.228` или выше |
| **Sodium (NeoForge)** | Обязательно | Версия `0.6.9+` |
| **Voxy** | Опционально / Зависит от ветки | Требуется только для ветки `voxy` (`voxy-neoforged-0.1.1+`) |
| **Lithium** | Опционально | Полная поддержка оптимизации коллизий |
| **Create Aeronautics / Sable** | Опционально | Поддерживаются твердотельные коллизии на высоте $Y > 320$ |

---

## Сборка из исходников

Требуется установленный **JDK 21**.

```bash
# Клонирование репозитория
git clone https://github.com/USERNAME/SkyConstruction.git
cd SkyConstruction

# Сборка базовой версии
git checkout SkyConstruction
./gradlew build

# Сборка Voxy-версии
git checkout SkyConstructionVoxy
./gradlew build
```

Собранный `.jar` будет располагаться в каталоге `build/libs/`.
