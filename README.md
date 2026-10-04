# Luxium: Decompiled & Remapped

[![Minecraft 1.20.1](https://img.shields.io/badge/Minecraft-1.20.1-brightgreen.svg)](https://minecraft.net/)
[![Forge 47.1.3+](https://img.shields.io/badge/Forge-47.1.3%2B-orange.svg)](https://files.minecraftforge.net/)
[![Embeddium](https://img.shields.io/badge/Dependency-Embeddium-blue.svg)](https://curseforge.com/)
[![Mappings: Mojang Official](https://img.shields.io/badge/Mappings-Mojang%20Official-red.svg)](https://minecraft.net/)

[English](#-english) • [Русский](#-русский)

---

## 🌐 English

Decompiled and completely remapped (Forge SRG → Mojang Mappings) source code of the performance graphics mod **Luxium: Let there be light** (`2.8.0-pre-alpha`).

- **Original Mod:** `Luxium Let there be light-2.8.0-pre-alpha.jar`
- **Original Author:** vinlanx
- **Target Platform:** Minecraft Forge `[47.1.3,)` (1.20.1), Java 17+
- **Dependencies:** Embeddium `[0.3.31,0.4)`

---

### 🛠️ Work Done & Pipeline

#### 1. Binary & Obfuscation Analysis
- The original JAR contained 395 compiled `.class` files and 255 resources (GLSL shaders, textures, JSON configurations).
- **No proprietary obfuscator was used by the author**: All custom classes (`NeoFloodRtEngine`, `TorchRtxState`, `AirDistortion`, etc.), method names, field names, and debug symbols (`LocalVariableTable`, `LineNumberTable`, `SourceFile`) remained intact.
- However, Minecraft API references were reobfuscated by ForgeGradle during compilation into Searge intermediate identifiers (SRG: `m_XXXXX_` and `f_XXXXX_`). In total, the mod referenced **482 SRG methods** and **189 SRG fields**.

#### 2. Mapping Generation (SRG → Mojang 1.20.1)
- Extracted official Mojang ProGuard client and server mappings for version `1.20.1`.
- Extracted `config/joined.tsrg` from MCPConfig `1.20.1-20230612.114412`.
- Developed [`BuildMappings.java`](tools/BuildMappings.java) (leveraging Forge `srgutils 0.6.2`) to construct bidirectional translation tables containing **33,222 methods** and **31,003 fields**.
- Achieved **100% coverage**: all 482 methods and 189 fields in the mod matched their official Mojang counterparts with zero missing symbols.

#### 3. Bytecode Remapping via ASM 9
- Built [`ModRemapper.java`](tools/ModRemapper.java) powered by ASM 9.7.1 (`ClassRemapper`).
- Successfully remapped:
  - Class declarations and method overrides;
  - Instruction calls (`INVOKEVIRTUAL`, `INVOKESTATIC`, `INVOKESPECIAL`, `INVOKEINTERFACE`);
  - Field access instructions (`GETFIELD`, `PUTFIELD`, `GETSTATIC`, `PUTSTATIC`);
  - Dynamic invocations (`invokedynamic` lambdas and method references);
  - String literals and injector targets in Mixin annotations (`@Inject`, `@Shadow`, `@Accessor`, `@At`, `@Overwrite`);
  - Mixin refmap metadata (`luxium.mixins.refmap.json`).
- Output: Clean remapped binary [`Luxium-2.8.0-pre-alpha-mojang-remapped.jar`](./Luxium-2.8.0-pre-alpha-mojang-remapped.jar).

#### 4. Decompilation via Vineflower 1.10.1
- Decompiled using the state-of-the-art **Vineflower 1.10.1** decompiler with full Java 17/21 feature support (pattern matching, lambda desugaring, LVT parameter naming).
- Produced 187 high-quality `.java` source files with readable variable names and Mojang Minecraft API calls.
- Zero unresolved `m_..._` or `f_..._` identifiers remain in the codebase.

---

### 📂 Repository Structure

```
.
├── src/
│   └── main/
│       ├── java/                 # Java source code (com.vinlanx.luxium.*)
│       └── resources/            # Shaders (assets/), META-INF/mods.toml, mixins
├── tools/                        # Reproducible remapping tooling
│   ├── BuildMappings.java        # SRG -> Mojang mapping generator
│   ├── ModRemapper.java          # ASM bytecode & mixin remapper
│   └── mappings_1_20_1/          # Dictionary lookup tables
├── Luxium-2.8.0-pre-alpha-mojang-remapped.jar  # Remapped mod JAR
├── Luxium-2.8.0-pre-alpha-sources.jar          # Sources archive
├── LICENSE.txt                   # Original License
├── logo.png                      # Mod Logo
└── README.md                     # Documentation
```

---

### ⚖️ License & Disclaimer
The original mod is created by **vinlanx**. Decompilation and remapping were conducted for educational purposes, code auditing, lighting algorithm study, and mod interoperability.

---

## 🇷🇺 Русский

Декомпилированный и полностью заремапленный (SRG → Mojang Mappings) исходный код графического мода **Luxium: Let there be light** (`2.8.0-pre-alpha`).

- **Оригинальный мод:** `Luxium Let there be light-2.8.0-pre-alpha.jar`
- **Автор оригинала:** vinlanx
- **Платформа:** Minecraft Forge `[47.1.3,)` (1.20.1), Java 17+
- **Зависимости:** Embeddium `[0.3.31,0.4)`

---

### 🛠️ Проделанная работа

#### 1. Анализ исходного JAR-файла
- В JAR-архиве находилось 395 скомпилированных `.class` файлов и 255 ресурсов (GLSL-шейдеры, текстуры, конфиги).
- Было определено, что **собственный код автора не был обфусцирован**: все классы (`NeoFloodRtEngine`, `TorchRtxState`, `AirDistortion` и др.), методы и поля сохранили свои оригинальные названия, а также отладочную информацию (`LocalVariableTable`, `LineNumberTable`, `SourceFile`).
- Однако обращения к коду Minecraft при компиляции через ForgeGradle были переведены в промежуточные идентификаторы Searge (SRG: `m_XXXXX_` и `f_XXXXX_`). Всего в моде насчитывалось **482 вызова SRG-методов** и **189 обращений к SRG-полям**.

#### 2. Генерация базы маппингов (SRG → Mojang 1.20.1)
- Извлечены официальные ProGuard-маппинги Mojang для клиента и сервера версии `1.20.1`.
- Извлечён файл связей `config/joined.tsrg` из дистрибутива MCPConfig `1.20.1-20230612.114412`.
- С помощью утилиты [`BuildMappings.java`](tools/BuildMappings.java) (на базе `srgutils 0.6.2`) сгенерированы точные таблицы соответствий для **33,222 методов** и **31,003 полей**.
- **Покрытие составило 100%**: все 482 метода и 189 полей мода нашли однозначное соответствие в официальных именах Mojang.

#### 3. Ремаппинг байткода на базе ASM 9
- Разработана утилита [`ModRemapper.java`](tools/ModRemapper.java) на базе ASM 9.7.1 (`ClassRemapper`).
- Заремаплены:
  - Определения и переопределения методов;
  - Инструкции вызова методов (`INVOKEVIRTUAL`, `INVOKESTATIC`, `INVOKESPECIAL`, `INVOKEINTERFACE`);
  - Инструкции работы с полями (`GETFIELD`, `PUTFIELD`, `GETSTATIC`, `PUTSTATIC`);
  - Динамические вызовы `invokedynamic` (лямбды и ссылки на методы);
  - Строковые литералы и цели инжекторов внутри аннотаций Mixin (`@Inject`, `@Shadow`, `@Accessor`, `@At`, `@Overwrite`);
  - Метаданные `luxium.mixins.refmap.json`.
- В результате получен полностью деобфусцированный байткод: [`Luxium-2.8.0-pre-alpha-mojang-remapped.jar`](./Luxium-2.8.0-pre-alpha-mojang-remapped.jar).

#### 4. Декомпиляция через Vineflower 1.10.1
- С использованием современного декомпилятора **Vineflower 1.10.1** весь байткод был декомпилирован в чистый Java-код.
- Восстановлены 187 `.java` файлов с чистым синтаксисом Java 17+, локальными переменными, switch pattern matching и восстановленными лямбдами.
- Ни одного неразрешённого `m_..._` или `f_..._` в кодовой базе не осталось.

---

### ⚖️ Лицензия и дисклеймер
Оригинальный мод создан разработчиком **vinlanx**. Декомпиляция и ремаппинг выполнены в исследовательских целях для аудита кода, анализа алгоритмов освещения и совместимости.
