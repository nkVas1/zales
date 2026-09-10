# Среда разработки с нуля (Windows)

Проверено на Windows 11, сентябрь 2026. Все идентификаторы пакетов winget проверены
командой `winget search --exact`.

Для работы над интерфейсом достаточно шагов 1–4. Go и NDK нужны только тем, кто
собирает нативные артефакты локально — обычно это делает CI.

---

## 1. JDK 21

```powershell
winget install --id EclipseAdoptium.Temurin.21.JDK --exact
```

Проверка: `java -version` должна показать `21.x`.

Если `JAVA_HOME` не выставился сам:

```powershell
[Environment]::SetEnvironmentVariable(
  'JAVA_HOME',
  (Get-ChildItem 'C:\Program Files\Eclipse Adoptium' -Directory | Where-Object Name -like 'jdk-21*').FullName,
  'User')
```

---

## 2. Android SDK

**Вариант А — только командная строка** (легче, быстрее, достаточно для сборки):

1. Скачать `commandlinetools-win-*.zip` со страницы Android Studio, раздел
   «Command line tools only».
2. Распаковать в `C:\Android\sdk\cmdline-tools\latest\` — именно с подпапкой `latest`,
   иначе `sdkmanager` откажется работать.
3. Задать переменные и поставить компоненты:

```powershell
[Environment]::SetEnvironmentVariable('ANDROID_HOME','C:\Android\sdk','User')
$env:ANDROID_HOME = 'C:\Android\sdk'
& "$env:ANDROID_HOME\cmdline-tools\latest\bin\sdkmanager.bat" --licenses
& "$env:ANDROID_HOME\cmdline-tools\latest\bin\sdkmanager.bat" `
    "platform-tools" "platforms;android-36" "build-tools;36.0.0"
```

**Вариант Б — Android Studio** (если нужен визуальный отладчик и профилировщик):

```powershell
winget install --id Google.AndroidStudio --exact
```

---

## 3. Телефон

1. Настройки → Сведения о телефоне → Сведения о ПО → семь раз нажать «Номер сборки».
2. Появится «Параметры разработчика» → включить **Отладка по USB**.
3. Подключить кабелем, разрешить отладку на телефоне.
4. Проверить: `adb devices` — устройство должно быть в списке со статусом `device`.

На Samsung дополнительно стоит выключить в параметрах разработчика ограничение
фоновых процессов, иначе отладка сервиса будет ложно «падать».

---

## 4. Сборка

```powershell
.\gradlew installDebug        # собрать и поставить на подключённый телефон
.\gradlew lint detekt test    # ворота качества
```

Первая сборка тянет Gradle и зависимости — минут десять. Дальше секунды.

---

## 5. Нативные артефакты (необязательно)

Обычно их собирает CI на Linux и кладёт в GitHub Release; Gradle забирает готовые.
Локальная сборка нужна только при правке самих нативных модулей.

```powershell
winget install --id GoLang.Go --exact
winget install --id Python.Python.3.12 --exact
go install golang.org/x/mobile/cmd/gomobile@latest
gomobile init
```

NDK ставится через `sdkmanager`:

```powershell
& "$env:ANDROID_HOME\cmdline-tools\latest\bin\sdkmanager.bat" "ndk;27.2.12479018"
```

Затем:

```powershell
python native\libxray\build.py android     # → zales-core.aar
ndk-build -C native\hev                    # → libhev.so
```

**Предупреждение.** Связка gomobile + NDK на Windows работает заметно хуже, чем на
Linux: путаются пути, ломается `cgo`. Если что-то не собирается — не тратьте время,
запустите workflow `native.yml` в GitHub Actions и скачайте артефакт оттуда. Это
предусмотренный основной путь, а не обходной.

---

## 6. Полезное

```powershell
adb logcat -s Zales:V ZalesTunnel:V     # только наши логи
adb shell dumpsys activity service ZalesVpnService
adb shell settings get global animator_duration_scale   # проверка «уменьшения движения»
```
