# Gem — клиент Gemini 3.5 Flash

Минималистичный Android-клиент для Google Gemini 3.5 Flash API. Чистый белый UI, 9 переключаемых API-ключей, история диалогов, прикрепление файлов, стриминг ответов с режимом thinking.

## Возможности

- Модель `gemini-3.5-flash` с `thinkingLevel = "high"`
- Server-Sent Events streaming (`alt=sse`)
- 9 слотов для API-ключей, шифруются через `EncryptedSharedPreferences`
- История диалогов сохраняется локально в JSON
- Прикрепление файлов: текстовые встраиваются в промт, бинарные — через `inlineData` (base64)
- Кэш base64 в памяти, чтобы не пересчитывать на каждый ход
- Отображение «процесса рассуждения» отдельным сворачиваемым блоком
- Кнопка Stop для прерывания генерации
- Копирование всего ответа и каждого блока кода

## Требования

| Компонент | Версия |
| --- | --- |
| Android | 8.0+ (minSdk 26) |
| compileSdk / targetSdk | 36 |
| Kotlin | 2.3.20 |
| AGP | 9.1.0 |
| Gradle | 9.4.1 |

## Сборка

CI/CD на GitHub Actions, локальная сборка не предполагается. При push в `main` workflow собирает debug APK и публикует его в Releases.

Перед первым билдом:

1. Запустить workflow `Generate Debug Keystore` вручную, скопировать base64.
2. Добавить secret `DEBUG_KEYSTORE_B64` в `Settings → Secrets and variables → Actions`.
3. Закоммитить любое изменение в `main` — APK появится в Releases.

## API-ключ

Получить в [Google AI Studio](https://aistudio.google.com/app/apikey). Открыть приложение → шестерёнка → вставить ключ в один из 9 слотов → сохранить. На главном экране переключается планшеткой в верхней части.

## Структура

```
app/src/main/java/com/gem/app/
├── MainActivity.kt    # Весь код: модели, репозиторий, сервис, ViewModel, UI
└── GeminiApp.kt       # @HiltAndroidApp точка входа
```