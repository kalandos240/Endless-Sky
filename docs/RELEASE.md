# Релиз для RuStore

Порт пока тестовый: публикация и проверка на реальном Android-устройстве не выполнены.
Постоянный applicationId нужно подтвердить до первой публикации; текущий —
`io.github.kalandos240.endlesssky`, без debug-суффикса.

## Постоянный ключ

Используйте свой существующий ключ либо создайте новый интерактивно через JDK 17:

```sh
keytool -genkeypair -storetype JKS -keystore /secure/path/endless-sky-release.jks \
  -alias endless-sky -keyalg RSA -keysize 4096 -validity 10000
```

Сохраните хранилище, alias и пароли в защищённом месте с резервной копией вне
рабочей машины. Для последующих обновлений нужны тот же пакет и сертификат.
Не используйте кешируемый debug-ключ CI. Не добавляйте ключи, пароли или env-файлы
в Git и не вставляйте пароли в команды терминала.

## Сборка на Linux

Сначала установите инструменты из README и подготовьте исходники:

```sh
python3 tools/prepare_android.py
```

Передайте переменные через менеджер секретов либо прочитайте пароли без вывода
в Bash. Путь лучше задавать абсолютным; относительный считается от `android/`.

```bash
export ES_RELEASE_STORE_FILE=/secure/path/endless-sky-release.jks
export ES_RELEASE_KEY_ALIAS=endless-sky
read -r -s -p 'Keystore password: ' ES_RELEASE_STORE_PASSWORD; echo
export ES_RELEASE_STORE_PASSWORD
read -r -s -p 'Key password: ' ES_RELEASE_KEY_PASSWORD; echo
export ES_RELEASE_KEY_PASSWORD
(cd android && ./gradlew :app:assembleRelease --no-daemon)
unset ES_RELEASE_STORE_PASSWORD ES_RELEASE_KEY_PASSWORD
```

Результат: `android/app/build/outputs/apk/release/app-release.apk`.
При отсутствии любого параметра или файла ключа релизная сборка останавливается.
Корректность alias и паролей проверяет Android Gradle Plugin при подписании.
Секреты не нужны для debug-сборки. Автоматическая публикация не настроена.

Проверьте пакет и подпись (Linux SDK, путь `ANDROID_HOME` должен быть задан):

```sh
python3 tools/check_apk.py android/app/build/outputs/apk/release/app-release.apk \
  --readelf "$ANDROID_HOME/ndk/28.2.13676358/toolchains/llvm/prebuilt/linux-x86_64/bin/llvm-readelf"
"$ANDROID_HOME/build-tools/35.0.0/apksigner" verify --verbose --print-certs \
  android/app/build/outputs/apk/release/app-release.apk
```

Сохраните SHA-256 сертификата и сверяйте его в следующих релизах. Для обновлений
увеличивайте `versionCode` в `android/app/build.gradle`. Версия сейчас остаётся
`0.3.0-dev`: настройка подписи сама по себе не превращает порт в готовый релиз.

## Перед модерацией

- Пройдите [проверки на устройстве](DEVICE_TESTS.md), включая установку и обновление
  подписанного release APK, пилотов, звук, управление и возврат из фона.
- Подготовьте карточку приложения и настоящие скриншоты; проверьте права на
  распространение игры и ресурсов, уведомления лицензий и доступность исходников
  именно публикуемой версии порта и его зависимостей.
- По текущей справке RuStore APK должен быть подписан, иметь уникальное имя пакета
  и размер не более 5 ГБ; обновления должны сохранять пакет и подпись и увеличивать
  номер версии. Проверьте справку повторно непосредственно перед отправкой.

Официальные источники, проверены 31 октября 2026:

- [Публикация приложений](https://www.rustore.ru/help/developers/publishing-and-verifying-apps/app-publication)
- [Подписание приложения](https://www.rustore.ru/help/guides/sign-apk)
- [Подписи APK и AAB](https://www.rustore.ru/help/developers/publishing-and-verifying-apps/app-publication/apk-signature)

Проверка правил магазина не заменяет тест на устройстве. Доступ к RuStore Консоли
и постоянный ключ издателя этим репозиторием не предоставляются.
