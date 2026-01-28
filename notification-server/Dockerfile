# Multi-stage build for notification-service

# Stage 1: Build
FROM maven:3.9-eclipse-temurin-17-alpine AS builder

WORKDIR /build

# Copy parent pom first for dependency caching
COPY pom.xml .
COPY notification-api/pom.xml notification-api/
COPY notification-core/pom.xml notification-core/
COPY notification-audit/pom.xml notification-audit/
COPY notification-kafka/pom.xml notification-kafka/
COPY notification-rest/pom.xml notification-rest/
COPY notification-channels/pom.xml notification-channels/
COPY notification-channels/notification-channel-email/pom.xml notification-channels/notification-channel-email/
COPY notification-channels/notification-channel-email/email-provider-smtp/pom.xml notification-channels/notification-channel-email/email-provider-smtp/
COPY notification-channels/notification-channel-email/email-provider-ses/pom.xml notification-channels/notification-channel-email/email-provider-ses/
COPY notification-channels/notification-channel-sms/pom.xml notification-channels/notification-channel-sms/
COPY notification-channels/notification-channel-sms/sms-provider-twilio/pom.xml notification-channels/notification-channel-sms/sms-provider-twilio/
COPY notification-channels/notification-channel-sms/sms-provider-sns/pom.xml notification-channels/notification-channel-sms/sms-provider-sns/
COPY notification-channels/notification-channel-whatsapp/pom.xml notification-channels/notification-channel-whatsapp/
COPY notification-channels/notification-channel-whatsapp/whatsapp-provider-twilio/pom.xml notification-channels/notification-channel-whatsapp/whatsapp-provider-twilio/
COPY notification-channels/notification-channel-whatsapp/whatsapp-provider-meta/pom.xml notification-channels/notification-channel-whatsapp/whatsapp-provider-meta/
COPY notification-channels/notification-channel-push/pom.xml notification-channels/notification-channel-push/
COPY notification-channels/notification-channel-push/push-provider-fcm/pom.xml notification-channels/notification-channel-push/push-provider-fcm/
COPY notification-channels/notification-channel-push/push-provider-apns/pom.xml notification-channels/notification-channel-push/push-provider-apns/
COPY notification-spring-boot-starter/pom.xml notification-spring-boot-starter/
COPY notification-server/pom.xml notification-server/

# Download dependencies (cached layer)
RUN mvn dependency:go-offline -B

# Copy source code
COPY . .

# Build the application
RUN mvn clean package -DskipTests -B

# Stage 2: Runtime
FROM eclipse-temurin:17-jre-alpine

LABEL maintainer="ifrugal"
LABEL description="Multi-tenant Notification Service"

# Create non-root user
RUN addgroup -S notification && adduser -S notification -G notification

WORKDIR /app

# Copy the built jar
COPY --from=builder /build/notification-server/target/notification-server-*.jar app.jar

# Create config directory
RUN mkdir -p /config && chown -R notification:notification /config

# Switch to non-root user
USER notification

# Expose port
EXPOSE 8080

# Health check
HEALTHCHECK --interval=30s --timeout=10s --start-period=30s --retries=3 \
    CMD wget --no-verbose --tries=1 --spider http://localhost:8080/actuator/health || exit 1

# JVM options
ENV JAVA_OPTS="-Xms256m -Xmx512m -XX:+UseG1GC -XX:MaxGCPauseMillis=100"

# Entry point
ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar app.jar"]
