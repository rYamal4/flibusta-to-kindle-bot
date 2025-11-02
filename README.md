# Flibusta to Kindle Bot

Telegram бот для поиска книг на Flibusta и отправки их на Kindle через email.

## Возможности

- 🔍 Поиск книг по названию (через команду `/search` или просто текстом)
- 📖 Просмотр подробной информации о книге (автор, количество страниц, аннотация)
- 📧 Автоматическая отправка книг на Kindle через email
- 🔒 Однопользовательский режим (только владелец может использовать бота)
- 🐳 Готовые Docker и Docker Compose файлы

## Требования

- Java 21 или выше
- Telegram Bot Token
- SMTP сервер для отправки писем (Gmail, Yandex, Mail.ru и др.)
- Kindle email адрес

## Быстрый старт с Docker

1. Клонируйте репозиторий:
```bash
git clone https://github.com/rYamal4/flibusta-to-kindle-bot.git
cd flibusta-to-kindle-bot
```

2. Создайте файл `.env` из примера:
```bash
cp .env.example .env
```

3. Заполните `.env` своими данными:
```env
TELEGRAM_BOT_TOKEN=your_bot_token_here
TELEGRAM_USER_ID=your_telegram_user_id
KINDLE_EMAIL=your_username@kindle.com
SMTP_HOST=smtp.gmail.com:587
SENDER_EMAIL=your_email@gmail.com
SENDER_PASSWORD=your_app_password
FLIBUSTA_URL=https://flibusta.is
```

4. Запустите бота:
```bash
docker-compose up -d
```

5. Проверьте логи:
```bash
docker-compose logs -f
```

## Настройка переменных окружения

### TELEGRAM_BOT_TOKEN
Получите токен от [@BotFather](https://t.me/BotFather):
1. Напишите `/newbot`
2. Следуйте инструкциям
3. Скопируйте токен

### TELEGRAM_USER_ID
Узнайте свой ID через [@userinfobot](https://t.me/userinfobot):
1. Напишите боту `/start`
2. Скопируйте ваш ID

### SMTP настройки

#### Gmail
- SMTP_HOST: `smtp.gmail.com:587`
- SENDER_PASSWORD: используйте [App Password](https://myaccount.google.com/apppasswords)

#### Yandex
- SMTP_HOST: `smtp.yandex.ru:587`
- SENDER_PASSWORD: обычный пароль от почты

#### Mail.ru
- SMTP_HOST: `smtp.mail.ru:587`
- SENDER_PASSWORD: обычный пароль от почты

### KINDLE_EMAIL
Ваш Kindle email адрес (например, `username@kindle.com`).

**Важно:** Добавьте `SENDER_EMAIL` в список одобренных адресов:
1. Зайдите на [Amazon Content and Devices](https://www.amazon.com/hz/mycd/myx)
2. Preferences → Personal Document Settings
3. Добавьте ваш email в "Approved Personal Document E-mail List"

### FLIBUSTA_URL
URL Flibusta. По умолчанию: `https://flibusta.is`

## Использование

### Команды

- `/start` - Приветственное сообщение с описанием команд
- `/search <название>` - Поиск книг по названию
- `/info <book_id>` - Подробная информация о книге
- `/send <book_id>` - Скачать и отправить книгу на Kindle

### Быстрый поиск

Просто напишите название книги боту без команды - бот автоматически начнёт поиск:
```
Достоевский
```

### Интерактивные кнопки

После поиска к каждой книге прикрепляются кнопки:
- **Инфо** - показать подробную информацию
- **На Kindle** - сразу отправить на Kindle

## Локальная разработка

### Сборка

```bash
./gradlew build
```

### Запуск

```bash
./gradlew run
```

### Запуск с JAR

```bash
./gradlew build
java -jar build/libs/send-to-kindle-bot-1.0-SNAPSHOT.jar
```

## Docker команды

```bash
# Сборка образа
docker-compose build

# Запуск
docker-compose up -d

# Остановка
docker-compose stop

# Перезапуск
docker-compose restart

# Удаление
docker-compose down

# Логи
docker-compose logs -f

# Статус
docker-compose ps
```

## Структура проекта

```
.
├── src/main/kotlin/
│   ├── Main.kt                    # Точка входа
│   ├── bot/
│   │   └── SendToKindleBot.kt     # Telegram бот
│   ├── config/
│   │   └── BotConfiguration.kt    # Конфигурация
│   ├── model/
│   │   ├── BookSummary.kt         # Модель краткой информации о книге
│   │   └── FullBookInfo.kt        # Модель полной информации о книге
│   └── service/
│       ├── FlibustaClient.kt      # Клиент для Flibusta
│       ├── IFlibustaClient.kt     # Интерфейс клиента
│       ├── KindleService.kt       # Сервис отправки на Kindle
│       └── IKindleService.kt      # Интерфейс сервиса
├── Dockerfile                     # Docker образ
├── docker-compose.yml             # Docker Compose конфигурация
├── .env.example                   # Пример переменных окружения
└── build.gradle.kts               # Gradle конфигурация
```

## Технологии

- **Kotlin** 2.2.21
- **Ktor Client** - HTTP клиент
- **Jsoup** - Парсинг HTML
- **kotlin-telegram-bot** - Telegram Bot API
- **Simple Java Mail** - Отправка email
- **dotenv-kotlin** - Загрузка .env файлов
- **Logback** - Логирование

## Безопасность

- Бот работает только для владельца (проверка `TELEGRAM_USER_ID`)
- Неавторизованные пользователи получают ссылку на репозиторий
- Файл `.env` с секретами не коммитится в Git
- Docker контейнер работает от непривилегированного пользователя

## Лицензия

MIT License

## Автор

[rYamal4](https://github.com/rYamal4)

## Ссылки

- [Telegram Bot API](https://core.telegram.org/bots/api)
- [Flibusta](https://flibusta.is)
- [Send to Kindle by Email](https://www.amazon.com/gp/sendtokindle/email)
