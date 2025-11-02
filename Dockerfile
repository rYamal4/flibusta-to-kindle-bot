# Stage 1: Build
FROM gradle:8.5-jdk21 AS builder

WORKDIR /app

# Копируем файлы сборки
COPY build.gradle.kts settings.gradle.kts gradle.properties ./
COPY gradle ./gradle

# Скачиваем зависимости
RUN gradle dependencies --no-daemon

# Копируем исходный код
COPY src ./src

# Собираем приложение
RUN gradle build --no-daemon -x test

# Stage 2: Runtime
FROM eclipse-temurin:21-jre

WORKDIR /app

# Копируем собранный jar
COPY --from=builder /app/build/libs/*.jar app.jar

# Создаём пользователя для запуска приложения
RUN useradd -m -u 1000 appuser && chown -R appuser:appuser /app
USER appuser

# Запускаем приложение
ENTRYPOINT ["java", "-jar", "app.jar"]
