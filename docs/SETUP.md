# Среда разработки с нуля (Windows)

Проверено на Windows 11 в сентябре 2026 — не по документации, а прогоном всех
команд подряд на чистой машине. Идентификаторы пакетов winget подтверждены
через `winget search --exact`.

Для работы над интерфейсом достаточно шагов 1–4. Go и NDK нужны только тому, кто
собирает нативные артефакты локально; обычно это делает CI.

---

## 1. JDK 21

```powershell
winget install --id EclipseAdoptium.Temurin.21.JDK --exact
```

`JAVA_HOME` обычно не выставляется сам:

```powershell
$jdk = (Get-ChildItem 'C:\Program Files\Eclipse Adoptium' -Directory |
        Where-Object Name -like 'jdk-21*' | Select-Object -First 1).FullName
[Environment]::SetEnvironmentVariable('JAVA_HOME', $jdk, 'User')
```

Проверка: `java -version` → `21.x`.

---

## 2. Android SDK

Android Studio не нужен — сборка идёт из командной строки.

**2.1. Скачать command line tools.** Актуальная версия узнаётся из индекса
репозитория, а не угадывается:

```powershell
$xml = [xml](Invoke-WebRequest 'https://dl.google.com/android/repository/repository2-4.xml').Content
($xml.sdk.remotePackage | Where-Object { $_.path -eq 'cmdline-tools;latest' }).archives.archive |
    Where-Object { $_.'host-os' -eq 'windows' } | Select-Object -ExpandProperty complete
```

На сентябрь 2026 это `commandlinetools-win-16111833_latest.zip` (cmdline-tools 23.0).
**Сверьте SHA-1 из того же ответа** — это единственная защита от подмены архива.

**2.2. Распаковать** так, чтобы получилось ровно
`C:\Android\sdk\cmdline-tools\latest\bin\` — папка `latest` обязательна, иначе
инструменты откажутся работать.

**2.3. Поставить компоненты.**

```powershell
[Environment]::SetEnvironmentVariable('ANDROID_HOME', 'C:\Android\sdk', 'User')
$env:ANDROID_HOME = 'C:\Android\sdk'
$a = "$env:ANDROID_HOME\cmdline-tools\latest\bin\android.exe"

& $a sdk install --sdk $env:ANDROID_HOME "platform-tools"
& $a sdk install --sdk $env:ANDROID_HOME "platforms;android-37.0"
& $a sdk install --sdk $env:ANDROID_HOME "platforms;android-37.2"
& $a sdk install --sdk $env:ANDROID_HOME "build-tools;37.0.0"
```

> **Что изменилось в 2026.** В cmdline-tools 23 `sdkmanager` заменён на `android`
> с подкомандами (`android sdk list`, `android sdk install`). Отдельное принятие
> лицензий через `--licenses` больше не требуется. Старые инструкции из интернета
> с `sdkmanager "platforms;android-XX"` тихо падают с кодом 9 — это оно.

Посмотреть, что доступно: `android sdk list --all --sdk C:\Android\sdk "platforms*"`.

---

## 3. `local.properties`

Файл не в репозитории (и не должен быть). Создайте его в корне проекта:

```properties
sdk.dir=C\:/Android/sdk
```

Двоеточие экранируется обратным слэшем — таков формат `.properties`. Android Lint
проверяет это и валит сборку, если написано иначе.

---

## 4. Сборка

Gradle отдельно ставить не нужно — в репозитории лежит wrapper.

```powershell
.\gradlew assembleDebug        # собрать APK
.\gradlew installDebug         # собрать и поставить на подключённый телефон
.\gradlew detekt lint          # ворота качества
.\gradlew assembleRelease      # релизная сборка с R8
```

Первая сборка тянет Gradle 9.7.1 и зависимости — минут десять. Дальше секунды.

Закреплённые версии живут в одном месте — [`gradle/libs.versions.toml`](../gradle/libs.versions.toml):
AGP 9.4.0, Kotlin 2.4.20, Compose BOM 2026.09.00, compileSdk 37, minSdk 26.

> **Ловушка AGP 9.** Kotlin теперь встроен в плагин Android. Применять
> `org.jetbrains.kotlin.android` поверх — ошибка сборки, а не безобидное дублирование.
> Заодно `CommonExtension` перестал быть генериком и потерял блочные перегрузки:
> вместо `defaultConfig { minSdk = 26 }` пишется `defaultConfig.minSdk = 26`.
> Оба случая уже учтены в `build-logic/`.

---

## 5. Телефон

1. Настройки → Сведения о телефоне → Сведения о ПО → семь раз нажать «Номер сборки».
2. В появившихся «Параметрах разработчика» включить **Отладка по USB**.
3. Подключить кабелем, разрешить отладку на телефоне.
4. Проверить: `adb devices` — устройство в списке со статусом `device`.

На Samsung дополнительно стоит снять ограничение фоновых процессов в параметрах
разработчика, иначе отладка сервиса будет выглядеть как ложные падения.

Отладочная сборка ставится рядом с релизной (`applicationId` с суффиксом `.debug`),
так что сломанный dev-билд никогда не отнимет у вас работающий туннель.

---

## 6. Нативные артефакты (необязательно)

Обычно их собирает CI на Linux и кладёт в GitHub Release; Gradle забирает готовые.
Локальная сборка нужна только при правке самих нативных модулей.

```powershell
winget install --id GoLang.Go --exact
winget install --id Python.Python.3.12 --exact
go install golang.org/x/mobile/cmd/gomobile@latest
gomobile init
& "$env:ANDROID_HOME\cmdline-tools\latest\bin\android.exe" sdk install --sdk $env:ANDROID_HOME "ndk;27.2.12479018"
```

**Предупреждение.** Связка gomobile + NDK на Windows работает заметно хуже, чем на
Linux: путаются пути, ломается `cgo`. Если не собирается — не тратьте время,
запустите workflow `native.yml` и скачайте артефакт. Это предусмотренный основной
путь, а не обходной. См. [ADR-0003](adr/0003-single-go-runtime.md).

---

## 7. Полезное

```powershell
adb logcat -s Zales:V ZalesTunnel:V                      # только наши логи
adb shell dumpsys activity service ZalesVpnService
adb shell settings get global animator_duration_scale    # проверка «уменьшения движения»
.\gradlew --stop                                          # прибить демон, если закапризничал
```
