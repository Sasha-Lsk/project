# Apk Tool

Приложение-декомпилятор/компилятор Android-приложений для сборки в **AIDE**.

- Пакет: `com.apktool.app`
- Название: **Apk Tool**
- Формат проекта: AIDE (папки `src/`, `res/`, `assets/`, `AndroidManifest.xml`, `project.properties`)

## Как собрать

1. Скопируйте папку проекта на устройство.
2. Откройте её в AIDE (`Open an existing Android project`).
3. AIDE сам сгенерирует `R.java` и соберёт APK (Run).

`minSdk 21`, `target android-33` — совместимо со старыми и новыми Android.

## Возможности

Интерфейс:
- Кнопка **Выбрать APK** и под ней **apk → java** / **apk → smali**.
- Кнопка **Выбрать папку** (декомпилированный проект) и под ней **java → apk** / **smali → apk**.
- Ползунок адаптации сборки под Android 8–14 (0 = не менять оригинальный SDK).
- Окно логов с полосой прогресса в %, ошибки — красным.
- Кнопки **Копировать** и **Очистить** (очистка сбрасывает логи и выбранные файлы/папки).
- Всплывающие уведомления (Toast) и диалог результата при успехе/ошибке.
- Пофайловый вывод распакованных/добавленных файлов.

Пути вывода:
```
/storage/emulated/0/Download/ApkTool/decompiler/   (apk -> java/smali)
/storage/emulated/0/Download/ApkTool/compiler/     (java/smali -> apk)
```

## Движки (в `assets/`)

- `engine/apktool.jar` — Apktool 2.7.0 (apk↔smali, ресурсы). Уже дексован, грузится через `DexClassLoader`.
- `engine/jadx.jar` — JADX (apk→java), вызывается через публичный API (без `System.exit`).
- `engine/apksig.jar` + `key/testkey.*` — подпись APK (схемы v1+v2+v3).
- `bin/<abi>/aapt`, `aapt2`, `zipalign` — нативные бинарники под arm64-v8a, armeabi-v7a, x86, x86_64. Распаковываются под ABI устройства и делаются исполняемыми в рантайме.

## Что работает

- **apk → smali** — apktool `d`.
- **apk → java** — jadx API. (Java-код декомпилируется; при декоде ресурсов jadx
  на Android выводит нефатальные ошибки XML-парсера — ресурсы всё равно
  выгружаются в `resources/`.)
- **smali → apk** — apktool `b --use-aapt2` + zipalign + подпись v1+v2+v3.
- **java → apk** — для каталогов формата jadx/AIDE (`resources/` или сам каталог
  с `AndroidManifest.xml` + `res/` + `classes.dex` + `lib/` + `assets/`):
  нативный конвейер **aapt2 compile → aapt2 link → добавление classes.dex/lib/
  assets → zipalign → подпись**. Не использует `java.awt`/`ImageIO`, работает
  на Android. framework для линковки — встроенный `assets/engine/android.jar`.

## Изменения (v5)

**Декомпиляция apk → java выдаёт формат AIDE.** Результат:

```
<имя>_java/
├── sources/     ← classes.dex, разобранный в .java (jadx)
└── resources/   ← AndroidManifest.xml, res, assets, lib, META-INF
```

`classes.dex` **не** кладётся файлом в вывод — AIDE сам компилирует `.java` из
`sources/` обратно в `classes.dex` при сборке проекта (именно так этот формат и
собирается в AIDE). Ранее код ошибочно добавлял `classes.dex` в `resources/` —
это ломало формат AIDE; теперь dex-файлы из вывода удаляются.

Открывайте папку `<имя>_java` в AIDE как проект. Если приложение использует
внешние библиотеки (Google Play Services, Apache HTTP и т.п.), подключите
соответствующие `.jar` в AIDE — как при обычной сборке java-проекта.

## Изменения (v4)

Исправлена сборка `java → apk` (aapt2) для декомпилированных apktool/jadx
ресурсов. Проверено end-to-end на реальном примере «camera lg» (нативный aapt2
на arm64): 138 ресурсов компилируются, `aapt2 link` создаёт валидный APK с
`resources.arsc`. Добавлены две автопочинки перед компиляцией:

- **apktool-заглушки drawable.** `<drawable name="X">false</drawable>` (старый
  формат, aapt2: «invalid drawable») → `<item type="drawable" name="X">@null</item>`.
- **enum/flag как сырые числа.** apktool пишет значения enum-атрибутов числами
  (`<item name="buyButtonText">5</item>`), строгий aapt2 требует имя. Читаем
  `attrs*.xml`, строим карту `attr → {число → имя}` и заменяем
  (`5` → `buy_with`, `-1` → `match_parent` и т.д.).

Также: компиляция ресурсов **пофайлово** (один битый файл не рушит всю сборку),
ресурсы копируются в рабочую папку (исходник не меняется), fallback у
`aapt2 link` без нестандартных флагов.

## Изменения (v3)

- **Исправлен `error=13 Permission denied` для aapt2/aapt/zipalign.** На Android
  нельзя выполнять бинарники из `/data/.../files/` (noexec). Бинарники теперь
  лежат в `libs/<abi>/libaapt2.so`, `libaapt.so`, `libzipalign.so` и
  выполняются из `nativeLibraryDir` — единственного места с правом на
  исполнение. В манифесте выставлен `android:extractNativeLibs="true"`.
- **Логи прокручиваются вручную.** Блок логов вынесен из внешнего ScrollView в
  отдельную область с собственной прокруткой; автопрокрутка срабатывает только
  когда вы уже внизу, и не мешает листать вверх.
- **Нефатальные ошибки jadx** (`disallow-doctype-decl`, ManifestAttributes,
  `.arsc`) больше не показываются красным — помечаются как `[jadx warn]`.
  Java-код и ресурсы выгружаются полностью, это лишь предупреждения парсера.

## Изменения (v2)

- `java → apk` переписан с apktool `b` (который на Android падал с
  `ExceptionInInitializerError` из-за `java.awt`/`ImageIO`) на прямой конвейер
  aapt2. Формат jadx-декомпиляции теперь собирается корректно.
- Проставляются системные свойства (`java.io.tmpdir`, `user.home`, `os.name`,
  `sun.arch.data.model` и т.д.), которых нет на Android — устраняет
  `ExceptionInInitializerError` у apktool/jadx.
- Улучшена диагностика: `ExceptionInInitializerError` разворачивается до
  реальной причины и печатается в лог со стеком.
- Добавлен `assets/engine/android.jar` (SDK framework с `resources.arsc`) для
  aapt2 link.

## Ограничение (честно)

Компиляция **сырых `.java` исходников** в dex требует Java-компилятора (ecj)
и дексера (d8) на устройстве — отдельного компилятора в предоставленных архивах
нет (AIDE держит ecj внутри себя). Поэтому `java → apk` рассчитан на каталоги,
где `classes.dex` уже присутствует (результат jadx/AIDE-декомпиляции), а не на
компиляцию произвольного Java-исходника. Направление **smali → apk** такого
ограничения не имеет.
