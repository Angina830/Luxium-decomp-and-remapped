# Luxium: Decompiled & Remapped

[![Minecraft 1.20.1](https://img.shields.io/badge/Minecraft-1.20.1-brightgreen.svg)](https://minecraft.net/)
[![Forge 47.1.3+](https://img.shields.io/badge/Forge-47.1.3%2B-orange.svg)](https://files.minecraftforge.net/)
[![Embeddium](https://img.shields.io/badge/Dependency-Embeddium-blue.svg)](https://curseforge.com/)
[![Mappings: Mojang Official](https://img.shields.io/badge/Mappings-Mojang%20Official-red.svg)](https://minecraft.net/)

Декомпилированный и полностью заремапленный (SRG → Mojang Mappings) исходный код графического мода **Luxium: Let there be light** (`2.8.0-pre-alpha`).

- **Оригинальный мод:** `Luxium Let there be light-2.8.0-pre-alpha.jar`
- **Автор оригинала:** vinlanx
- **Платформа:** Minecraft Forge `[47.1.3,)` (1.20.1), Java 17+
- **Зависимости:** Embeddium `[0.3.31,0.4)`

---

## 🛠️ Проделанная работа

### 1. Анализ исходного JAR-файла
- В JAR-архиве находилось 395 скомпилированных `.class` файлов и 255 ресурсов (GLSL-шейдеры, текстуры, конфиги).
- Было определено, что **собственный код автора не был обфусцирован**: все классы (`NeoFloodRtEngine`, `TorchRtxState`, `AirDistortion` и др.), методы и поля сохранили свои оригинальные названия, а также отладочную информацию (`LocalVariableTable`, `LineNumberTable`, `SourceFile`).
- Однако обращения к коду Minecraft при компиляции через ForgeGradle были переведены в промежуточные идентификаторы Searge (SRG: `m_XXXXX_` и `f_XXXXX_`). Всего в моде насчитывалось **482 вызова SRG-методов** и **189 обращений к SRG-полям**.

### 2. Генерация базы маппингов (SRG → Mojang 1.20.1)
- Извлечены официальные ProGuard-маппинги Mojang для клиента и сервера версии `1.20.1`.
- Извлечён файл связей `config/joined.tsrg` из дистрибутива MCPConfig `1.20.1-20230612.114412`.
- С помощью утилиты [`BuildMappings.java`](tools/BuildMappings.java) (на базе `srgutils 0.6.2`) сгенерированы точные таблицы соответствий для **33,222 методов** и **31,003 полей**.
- **Покрытие составило 100%**: все 482 метода и 189 полей мода нашли однозначное соответствие в официальных именах Mojang.

### 3. Ремаппинг байткода на базе ASM 9
- Разработана утилита [`ModRemapper.java`](tools/ModRemapper.java) на базе ASM 9.7.1 (`ClassRemapper`).
- Заремаплены:
  - Определения и переопределения методов;
  - Инструкции вызова методов (`INVOKEVIRTUAL`, `INVOKESTATIC`, `INVOKESPECIAL`, `INVOKEINTERFACE`);
  - Инструкции работы с полями (`GETFIELD`, `PUTFIELD`, `GETSTATIC`, `PUTSTATIC`);
  - Динамические вызовы `invokedynamic` (лямбды и ссылки на методы);
  - Строковые литералы и цели инжекторов внутри аннотаций Mixin (`@Inject`, `@Shadow`, `@Accessor`, `@At`, `@Overwrite`);
  - Метаданные `luxium.mixins.refmap.json`.
- В результате получен полностью деобфусцированный байткод: [`Luxium-2.8.0-pre-alpha-mojang-remapped.jar`](./Luxium-2.8.0-pre-alpha-mojang-remapped.jar).

### 4. Декомпиляция через Vineflower 1.10.1
- С использованием современного декомпилятора **Vineflower 1.10.1** весь байткод был декомпилирован в чистый Java-код.
- Восстановлены 187 `.java` файлов с чистым синтаксисом Java 17+, локальными переменными, switch pattern matching и восстановленными лямбдами.
- Ни одного неразрешённого `m_..._` или `f_..._` в кодовой базе не осталось.

---

## 📂 Структура репозитория

```
.
├── src/
│   └── main/
│       ├── java/                 # Исходный Java-код мода (com.vinlanx.luxium.*)
│       └── resources/            # Ресурсы, шейдеры (assets/), META-INF/mods.toml, mixins
├── tools/                        # Исходный код утилит для ремаппинга
│   ├── BuildMappings.java        # Генератор связей SRG -> Mojang
│   ├── ModRemapper.java          # ASM-ремаппер байткода и миксинов
│   └── mappings_1_20_1/          # Словари методов и полей
├── Luxium-2.8.0-pre-alpha-mojang-remapped.jar  # Заремапленный JAR мода
├── Luxium-2.8.0-pre-alpha-sources.jar          # Архив исходников
├── LICENSE.txt                   # Лицензия
├── logo.png                      # Логотип мода
└── README.md                     # Описание проекта
```

---

## ⚖️ Лицензия и дисклеймер
Оригинальный мод создан разработчиком **vinlanx**. Декомпиляция и ремаппинг выполнены в исследовательских целях для аудита кода, анализа алгоритмов освещения и совместимости.
