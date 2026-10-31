# Релиз для RuStore

Порт пока тестовый: публикация и проверка на реальном Android-устройстве не выполнены.
Постоянный applicationId нужно подтвердить до первой публикации; текущий —
`io.github.kalandos240.endlesssky`, без debug-суффикса.

## Постоянный ключ

Используйте свой существующий ключ либо создайте новый интерактивно через JDK 17:

```sh
keytool -genkeypair -storetype PKCS12 -keystore /secure/path/endless-sky-release.p12 \
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
export ES_RELEASE_STORE_FILE=/secure/path/endless-sky-release.p12
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
увеличивайте `versionCode` в `android/app/build.gradle`. Версия сейчас `0.4.0` (debug: `0.4.0-dev`); номер версии сам по себе не означает готовность к публикации.

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

## Созданный постоянный ключ

31 октября 2026 создан PKCS12-ключ, alias `endless-sky`, RSA 4096,
SHA256withRSA, сертификат действителен до 18 марта 2054.
Открытый сертификат: [endless-sky-signing.crt](signing/endless-sky-signing.crt).
SHA-256 сертификата:

```text
AA:BF:89:11:92:C0:CF:C2:00:8C:C0:BF:52:4D:14:71:50:AB:50:FF:6C:E4:B5:69:E7:4B:E9:1C:E1:06:D6:D8
```

Закрытый ключ и пароль находятся только в переданных владельцу резервных файлах,
в Git их нет. Автоматически на GitHub ключ не загружался; credentials CI не настроены.
Этот сертификат самоподписанный: он предназначен для подписи Android APK, а не
для подтверждения личности издателя. Проверены загрузка PKCS12, подпись RSA-SHA256
и проверка подписи открытым сертификатом; APK этим ключом пока не подписан.

Чтобы собрать release с рабочим рекламным блоком после установки переменных
ключа из инструкции выше:

```sh
cd android
./gradlew -PesAds=yandex :app:assembleRelease --no-daemon
```

В этом режиме release использует `R-M-20312738-1`, debug всегда использует
`demo-interstitial-yandex`. Без `esAds` по-прежнему собирается версия без SDK.
Для `yandexTest` release запрещён. В описании рекламной версии для магазина
должна присутствовать фраза: «Создано с использованием Yandex Mobile Ads SDK».
В самой рекламной конфигурации она добавлена в «О приложении» и APK assets.
Включение режима сборки не меняет вывод [проверки лицензий](LICENSE_REVIEW.md).

## Подпись вне CI

Workflow `Android release for offline signing` собирает SDK-релиз с
`-PesAds=yandex -PesOfflineSigning=true`. Это явно выбранный неподписанный
промежуточный пакет, который сам по себе нельзя установить или опубликовать.
Обычная release-сборка по-прежнему требует ключ. CI не получает закрытый ключ;
подпись постоянным ключом ставится после скачивания и проверки артефакта.
Артефакт содержит выровненный APK и `apksigner.jar` из Build Tools 35.0.0.

```sh
java -jar apksigner.jar sign --ks /secure/path/endless-sky-release.p12 \
  --ks-type PKCS12 --ks-key-alias endless-sky \
  --ks-pass file:/secure/path/password.txt \
  --min-sdk-version 26 --v1-signing-enabled false \
  --out endless-sky-0.4.0-arm64.apk endless-sky-0.4.0-arm64-unsigned.apk
java -jar apksigner.jar verify --verbose --print-certs endless-sky-0.4.0-arm64.apk
```

Проверьте SHA-256 сертификата по открытому сертификату в этом репозитории.
Не изменяйте ZIP/APK после подписи. После скачивания подписанного APK остаётся
ручная проверка на устройстве; успешная сборка её не заменяет.
