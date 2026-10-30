# Endless Sky — Android / RuStore

Начальный этап переноса [Endless Sky](https://github.com/endless-sky/endless-sky) на Android для последующего выпуска в RuStore.

**Статус: нативная основа порта. APK ещё не собирается.** Android-оболочка, автоматическая сборка зависимостей, установка ресурсов и сенсорное управление пока не реализованы.

## Исходники

Исходная версия закреплена в `upstream-revision.txt`; изменения хранятся в `patches/`. Это самостоятельный репозиторий порта, не GitHub-форк upstream.

```sh
python3 tools/prepare_upstream.py
```

Команда получает точный commit в `build/upstream` и применяет патчи. Повторный запуск распознаёт уже применённый патч. Существующий checkout другой версии не перезаписывается.

## Что подготовлено

- Android-цель `libmain.so` для SDL2 `SDLActivity`, вместо desktop executable.
- OpenGL ES 3 через библиотеки Android NDK `GLESv3` и `EGL`; исключён поиск GLEW.
- Position-independent code, отключение desktop test runners и desktop sanitizer flags для Android.
- Потоки через `Threads::Threads`: отдельного `libpthread` в Android нет.
- Явный поиск cross-compiled libuuid: Bionic не предоставляет `uuid/uuid.h`.
- Чтение распакованных ресурсов из `<internal storage>/resources`. Android launcher должен установить их до вызова `SDL_main`; сейчас такого launcher нет.
- Явное включение SDL в `main.cpp` для Android entry point.

## Следующие этапы

1. Собрать и закрепить версии Android-зависимостей: SDL2, PNG, JPEG, AVIF, OpenAL, FLAC++, libmad, zlib/minizip и libuuid. Все native-библиотеки должны соответствовать ABI и NDK.
2. Добавить Gradle-приложение с SDLActivity из той же версии SDL2, AndroidManifest и arm64-v8a. Выбрать applicationId перед первым выпуском.
3. Упаковать `data/`, `images/`, `shaders/`, `sounds/`, `credits.txt`, `keys.txt` и лицензионные материалы. Реализовать установку ресурсов с проверкой версии и свободного места.
4. Проверить запуск, звук, сохранения, сворачивание/возврат и восстановление GL context на устройстве. Добавить сенсорные элементы управления и адаптацию интерфейса.
5. Проверить актуальные требования RuStore, затем подготовить подпись, карточку приложения и пакет для модерации. SDK RuStore, платежи и аналитика пока не добавлены.

## Проверка текущего этапа

Проверены `git diff --check`, применение патча к закреплённому upstream и повторный запуск подготовки. Пробная CMake-конфигурация дошла до поиска SDL2 и остановилась из-за отсутствия зависимости. Полная Android-компиляция и запуск не проверены: в рабочей среде нет SDK/NDK и Android-библиотек.

## Лицензии

Код Endless Sky распространяется по GPL-3.0-or-later; ресурсы имеют отдельные условия, перечисленные в upstream `copyright`. При распространении порта необходимо сохранить уведомления и предоставить соответствующий исходный код. Этот репозиторий не содержит сами ресурсы игры.
