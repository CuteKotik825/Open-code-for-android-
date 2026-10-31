<div align="center">

# PocketCode

**Кодинг-агент, который живёт в твоём телефоне.**
Пишешь задачу в чате, а агент сам читает и правит файлы, запускает команды, Python и git. ПК не нужен.

[![Сборка](https://github.com/CuteKotik825/Open-code-for-android-/actions/workflows/build.yml/badge.svg)](https://github.com/CuteKotik825/Open-code-for-android-/actions/workflows/build.yml)
[![Релиз](https://img.shields.io/github/v/release/CuteKotik825/Open-code-for-android-?include_prereleases&label=%D1%81%D0%B1%D0%BE%D1%80%D0%BA%D0%B0)](https://github.com/CuteKotik825/Open-code-for-android-/releases/latest)
[![Лицензия: MIT](https://img.shields.io/badge/license-MIT-blue.svg)](LICENSE)
![Android 8+](https://img.shields.io/badge/Android-8.0%2B-3DDC84?logo=android&logoColor=white)
[![Telegram](https://img.shields.io/badge/Telegram-чат-26A5E4?logo=telegram&logoColor=white)](https://t.me/pocket_code_github)

[**Скачать APK**](https://github.com/CuteKotik825/Open-code-for-android-/releases/latest) · [**Чат в Telegram**](https://t.me/pocket_code_github) · [Сообщить об ошибке](https://github.com/CuteKotik825/Open-code-for-android-/issues/new/choose)

</div>

---

## Что это

PocketCode работает как OpenCode, Claude Code или Codex, только целиком на Android (Kotlin + Jetpack Compose). Подключаешь модель по API-ключу, открываешь проект и ставишь задачу. Агент читает код, правит файлы, запускает команды и тесты, делает коммиты. Работа идёт в фоне: можно свернуть приложение или выключить экран.

## Возможности

| | |
|---|---|
| **Агент** | Инструменты `read_file`, `edit_file`, `bash`, `python`, `git`, `grep`, веб-поиск, MCP, подагенты, режим плана, список задач, правила подтверждений |
| **Любая модель** | Anthropic, OpenAI-совместимые API (OpenAI, OpenRouter, Gemini, Groq, NVIDIA и свои адреса), шаблоны провайдеров, сравнение моделей |
| **Режим OpenCode** | Официальный клиент OpenCode внутри Linux на телефоне: беседы, несколько аккаунтов, лимиты и повторы, запасная модель, свой прокси |
| **Linux на телефоне** | Alpine в proot без root: `apk add python3 nodejs git`, проект виден как `/work` |
| **Проекты** | У каждого своя папка и история, несколько чатов во вкладках, снимки перед задачами, откат правок по блокам |
| **Два телефона** | Синхронизация проектов и пульт: ставишь задачу на втором телефоне, выполняет первый (через приватный репозиторий GitHub, свой сервер не нужен) |
| **Удобство** | Нижняя панель (Чат · Проект · Агент · Ещё), тёмная тема, режим AMOLED, цвета на выбор, шаблоны запросов, отправка «позже», уведомления |
| **Безопасность** | Подтверждение опасных действий, защита приложения отпечатком или PIN, шифрованная копия настроек |

## Установка

1. Открой [**Releases**](https://github.com/CuteKotik825/Open-code-for-android-/releases/latest) на телефоне и скачай `PocketCode.apk`.
2. Разреши установку из этого источника и поставь приложение.
3. **Ещё → Настройки**: выбери провайдера и вставь API-ключ.
4. Нажми на название проекта вверху, создай проект или открой папку телефона и напиши задачу, например: «найди, где обрабатывается вход, и добавь проверку пустого пароля».

Нужен Android 8.0 или новее. Обновления ставятся поверх без потери настроек.

> **Linux и OpenCode.** В **Проекты → Установить Linux** скачивается Alpine (~4 МБ). Потом в **Терминале** кнопка «Установить OpenCode» поставит остальное сама.

## Как собрать самому

```bash
git clone https://github.com/CuteKotik825/Open-code-for-android-.git
cd Open-code-for-android-
gradle :app:assembleDebug     # нужны JDK 17 и Android SDK
```

Или без ПК: **Actions → Build APK → Run workflow**, готовый APK появится в разделе Releases.

<details>
<summary>Подпись своим ключом</summary>

По умолчанию сборки подписаны общим ключом `app/debug.keystore` (он лежит в репозитории, пароль `android`), поэтому он подходит только для личного пользования. Для своих сборок создай свой ключ:

```bash
keytool -genkeypair -v -keystore release.jks -alias pocketcode -keyalg RSA -keysize 4096 -validity 36500
base64 -w0 release.jks     # результат положи в секрет PC_KEYSTORE_B64
```

В **Settings → Secrets and variables → Actions** добавь `PC_KEYSTORE_B64`, `PC_STORE_PASS`, `PC_KEY_ALIAS`, `PC_KEY_PASS`. Дальше CI подпишет APK твоим ключом. Файл `release.jks` и пароли храни отдельно: потеряешь ключ — обновить приложение у пользователей будет нельзя. Подпись менять нельзя «на ходу»: после смены ключа приложение надо переустановить (настройки сохрани через «Диагностика → Копия настроек»).

</details>

## Известные ограничения

- Проверено на реальных телефонах автора, но не на всех прошивках. Если proot не запускается, пришли отчёт из «Диагностики».
- Операторы связи в РФ могут обрывать трафик к Cloudflare (в том числе к `opencode.ai`): при ошибках `ECONNRESET` включи VPN и добавь в него PocketCode, либо укажи прокси в настройках OpenCode.
- Нет systemd и демонов, `pip` в встроенном Python недоступен (в Linux можно).
- Токены считаются приблизительно (~3 символа на токен).
- Доступ ко всем файлам телефона не пройдёт модерацию Google Play, поэтому приложение распространяется как APK.
- Бесплатные модели OpenCode имеют лимиты на стороне сервиса. Приложение их не обходит, а помогает пережить (повторы, запасная модель, второй аккаунт).

## Структура

```
app/src/main/java/com/pocketcode/
  llm/      клиенты Anthropic и OpenAI-совместимых API
  tools/    инструменты агента (файлы, bash, python, git)
  agent/    цикл агента, сжатие контекста, системный промпт
  linux/    Alpine + proot
  python/   Python в отдельном процессе
  session/  сессии и снимки проектов
  service/  фоновый сервис и уведомления
  ui/       чат, разделы, настройки, Markdown, темы
```

## Сообщество

Вопросы, идеи и новости: **[t.me/pocket_code_github](https://t.me/pocket_code_github)**. Баги и предложения принимаются в [Issues](https://github.com/CuteKotik825/Open-code-for-android-/issues).

## Лицензия

[MIT](LICENSE). В APK входят готовые сборки PRoot (GPL-2.0+) из [build-proot-android](https://github.com/green-green-avk/build-proot-android), подробности в [THIRD_PARTY_PROOT.txt](THIRD_PARTY_PROOT.txt).
