# ApkStudio — декомпилятор/компилятор APK для AIDE

Простое Android-приложение: **APK → Smali / APK → Java** и обратно
**Smali → APK / Java → APK**. Всё выполняется прямо на телефоне,
собирается в **AIDE** без ошибок.

---

## 1. Как собрать это приложение в AIDE

1. Распакуйте архив `ApkStudio_project.zip` в
   `/storage/emulated/0/AppProjects/ApkStudio/` (или любую папку).
2. В AIDE: **Меню → Открыть проект** → выберите папку `ApkStudio`.
3. Дождитесь индексации и нажмите **Выполнить (Run)**. AIDE сам скомпилирует,
   упакует и подпишет APK. Приложение установится.

Структура проекта — стандарт AIDE (как в вашем рабочем примере
`android ide mobile`):

```
ApkStudio/
├── AndroidManifest.xml        ← манифест (package com.apkstudio.app)
├── project.properties         ← target=android-33
├── src/com/apkstudio/app/     ← исходники .java
├── res/                       ← ресурсы (layout, values, drawable, xml, mipmap)
├── assets/engine/             ← apktool.jar, jadx.jar, apksig.jar + ключи
└── libs/<abi>/                ← нативные aapt/aapt2/zipalign (как lib*.so)
```

### Почему бинарники лежат как `lib*.so` в `libs/<abi>/`
С Android 10 действует политика **W^X**: запускать исполняемые файлы из
папки данных приложения (`/data/data/...`) запрещено. Единственное место,
откуда система разрешает запуск — `nativeLibraryDir`. Туда попадают только
файлы из `libs/<abi>/`, названные как `lib*.so`. Поэтому:

| Инструмент | Файл в проекте          | Где окажется на телефоне            |
|------------|-------------------------|-------------------------------------|
| aapt2      | `libs/<abi>/libaapt2.so`| `nativeLibraryDir/libaapt2.so`      |
| aapt       | `libs/<abi>/libaapt.so` | `nativeLibraryDir/libaapt.so`       |
| zipalign   | `libs/<abi>/libzipalign.so`| `nativeLibraryDir/libzipalign.so`|

В манифесте стоит `android:extractNativeLibs="true"`, чтобы файлы
распаковались на диск (иначе они лежат сжатыми внутри APK и не запустятся).

---

## 2. Рабочая папка

Всё лежит в одной директории (создаётся автоматически):

```
/storage/emulated/0/Download/ApkStudio/
├── decompiler_apk/            ← сюда РАСПАКОВЫВАЕТСЯ APK
│   ├── <имя>/                 ←   результат APK → Smali (apktool-проект)
│   └── <имя>_java/            ←   результат APK → Java (только для чтения)
└── compiler_apk/              ← сюда СОБИРАЕТСЯ готовый .apk
    └── <имя>.apk              ←   подписанный, готов к установке
```

---

## 3. Правильный порядок работы (важно!)

### Модификация чужого приложения — через Smali (надёжный путь)

1. **APK → Smali** — выберите APK, нажмите кнопку. Получите проект в
   `decompiler_apk/<имя>/` со стандартной раскладкой apktool:

   ```
   <имя>/
   ├── AndroidManifest.xml     ← манифест (уже раскодирован из бинарного)
   ├── apktool.yml             ← метаданные (версии SDK, имя пакета) — НЕ удалять
   ├── res/                    ← ресурсы (values, layout, drawable…)
   ├── smali/                  ← код: classes.dex → smali
   ├── smali_classes2/ …       ← multidex (если было несколько classes.dex)
   ├── assets/                 ← ассеты как в оригинале
   ├── lib/                    ← нативные .so оригинала
   └── unknown/                ← прочие файлы (META-INF и т.п.)
   ```

2. Редактируйте `.smali`-файлы (в любом редакторе / в AIDE).
   Для понимания логики используйте **APK → Java** как справочник
   (см. `<имя>_java/sources/`), но правки вносите в **smali**.

3. **Smali → APK** — выберите папку проекта `<имя>`, нажмите кнопку.
   Приложение выполнит: `apktool build` → `zipalign` → подпись (v1+v2+v3).
   Готовый `compiler_apk/<имя>.apk` можно устанавливать.

### APK → Java (jadx)

Даёт читаемый Java-код в `<имя>_java/sources/`.

### Java → APK — сборка ИЗ ПАПКИ ИСХОДНИКОВ (как в AIDE, БЕЗ оригинала)

Кнопка **Java → APK** собирает готовый подписанный APK прямо из папки с
исходниками — **оригинальный APK не нужен**. Работает так же, как AIDE:

```
aapt2 compile+link  →  R.java + base.apk (ресурсы)
        ↓
ecj (Eclipse Java Compiler)   .java + R.java → .class
        ↓
d8                            .class → classes.dex
        ↓
упаковка (dex+assets+lib в base.apk) → zipalign → подпись v1+v2+v3
```

Автоопределяются раскладки проекта:

| Раскладка | Исходники | Ресурсы | Манифест |
|-----------|-----------|---------|----------|
| **AIDE/Eclipse** | `src/` | `res/` | `AndroidManifest.xml` |
| **Gradle** | `app/src/main/java` | `app/src/main/res` | `app/src/main/AndroidManifest.xml` |
| **jadx-вывод** | `sources/` | `resources/res` | `resources/AndroidManifest.xml` |

То есть **само это приложение ApkStudio** (папка `src/` + `res/` +
`AndroidManifest.xml`) теперь собирается кнопкой **Java → APK** без всякого
apktool и без исходного APK.

> Примечание про jadx-Java: декомпилированный jadx-код может содержать
> `// decompilation error` в сложных местах. Компилятор ecj запускается с
> `-proceedOnError`, поэтому собирает всё, что валидно; сломанные места нужно
> поправить вручную (как и в AIDE). Для гарантированной пересборки чужого APK
> без правок кода по-прежнему удобен путь через **Smali → APK**.

---

## 4. Совместимость со старыми Android (8, 9, 10, 11, 12)

Галочка **«Поднять targetSdk до 33»** (включена по умолчанию) при сборке
меняет `targetSdkVersion` в манифесте и `apktool.yml` на 33. Это убирает окно
_«Это приложение разработано для более старой версии системы…»_, которое
показывают Android 10+ для APK с низким targetSdk.

`minSdkVersion` не трогается — совместимость «вниз» сохраняется, приложение
продолжит запускаться на старых версиях.

Подпись схемами **v1 + v2 + v3** гарантирует установку на всём диапазоне
Android 5–14.

---

## 5. Журнал и ошибки

- Весь процесс (каждая команда, вывод aapt2/apktool, ошибки) пишется в
  **детальный журнал** внизу экрана с подсветкой.
- Кнопка **«Копировать логи»** (справа вверху журнала) копирует весь текст —
  удобно переслать для диагностики.
- После операции появляется диалог с предложением **открыть папку** результата
  в системном приложении.

---

## 6. Что внутри toolchain

| Файл                        | Назначение                                  |
|-----------------------------|---------------------------------------------|
| `assets/engine/apktool.jar` | apktool 2.7.0 (dex-jar) — smali ↔ apk, res  |
| `assets/engine/jadx.jar`    | jadx (dex-jar) — dex → Java                  |
| `assets/engine/apksig.jar`  | apksig (dex-jar) — подпись v1/v2/v3          |
| `assets/engine/ecj.jar`     | Eclipse Java Compiler (dex-jar) — .java → .class |
| `assets/engine/d8.jar`      | d8/r8 (dex-jar) — .class → classes.dex       |
| `assets/engine/android.jar` | SDK-заглушки (android-30) — classpath для ecj/d8 и `-I` для aapt2 link |
| `assets/engine/testkey.*`   | тестовый ключ+сертификат для подписи         |
| `libs/<abi>/libaapt2.so`    | aapt2 — компиляция/линковка ресурсов         |
| `libs/<abi>/libzipalign.so` | zipalign — выравнивание APK                   |

jar-ы имеют внутри `classes.dex`, поэтому грузятся `DexClassLoader`-ом и
вызываются их **библиотечные API** прямо в процессе (без `main()`/`System.exit`,
который убил бы приложение).

---

## Ограничения (честно)

- **Java → APK из папки исходников работает как в AIDE** (ecj + d8 + aapt2),
  оригинальный APK не требуется. Но если исходники — это jadx-декомпиляция
  чужого APK со `// decompilation error`, компилируется только валидная часть;
  битые места надо поправить руками (это ограничение декомпиляции, не сборки).
  Для пересборки чужого APK без правок кода надёжнее путь через **smali**.
- Для сборки нужен ABI-совпадающий `aapt2`. В проекте есть бинарники для
  arm64-v8a, armeabi-v7a, x86, x86_64 — покрывают все реальные телефоны.
- `android.jar` в проекте — уровня android-30 (совместим с bundled aapt2 2.19).
  Приложение всё равно запускается на новых Android; при желании поднимите
  `targetSdk` галочкой «Поднять targetSdk до 33».
