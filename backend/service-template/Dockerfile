# syntax=docker/dockerfile:1
# =====================================================================
# service-template — çok aşamalı, distroless final image.
# Build context = mono-repo backend kökü (multi-module build tüm modülleri ister).
# Build komutu (backend/ dizininden):
#   docker build -f service-template/Dockerfile -t lumix/service-template:local .
#
# Aşamalar:  1) Gradle ile bootJar   2) jlink ile küçük JRE   3) distroless runtime
# Sonuç: distroless taban + trimmed JRE → tipik olarak < 200 MB.
# =====================================================================

# ---- Aşama 1: uygulamayı derle (bootJar → app.jar) ----
FROM eclipse-temurin:25-jdk AS build
WORKDIR /workspace
# Önce wrapper + build tanımları: bağımlılık katmanı cache'lensin.
COPY gradlew settings.gradle.kts build.gradle.kts gradle.properties ./
COPY gradle ./gradle
COPY config ./config
COPY service-template ./service-template
RUN chmod +x ./gradlew && ./gradlew --no-daemon :service-template:bootstrap:bootJar

# ---- Aşama 2: uygulamaya özel küçük JRE (jlink) ----
FROM eclipse-temurin:25-jdk AS jre-build
# Cömert ama tam-JDK'dan çok daha küçük modül seti (Spring Boot + JPA + Kafka + gRPC).
RUN "$JAVA_HOME/bin/jlink" \
    --add-modules java.base,java.desktop,java.instrument,java.logging,java.management,java.naming,java.net.http,java.prefs,java.rmi,java.scripting,java.security.jgss,java.security.sasl,java.sql,java.sql.rowset,java.transaction.xa,java.xml,java.xml.crypto,jdk.crypto.cryptoki,jdk.crypto.ec,jdk.jfr,jdk.management,jdk.naming.dns,jdk.unsupported \
    --strip-debug --no-header-files --no-man-pages --compress=zip-6 \
    --output /javaruntime

# ---- Aşama 3: distroless runtime ----
FROM gcr.io/distroless/base-debian12:nonroot
ENV JAVA_HOME=/opt/java/jre
ENV PATH="${JAVA_HOME}/bin:${PATH}"
COPY --from=jre-build /javaruntime $JAVA_HOME

WORKDIR /app
COPY --from=build /workspace/service-template/bootstrap/build/libs/app.jar /app/app.jar

# 8080 = HTTP/actuator, 9090 = gRPC
EXPOSE 8080 9090

# MaxRAMPercentage: container memory limit'inin %75'i heap'e (kalan metaspace/thread/JIT).
# Liveness/readiness K8s httpGet ile (/actuator/health/liveness|readiness) — image'da shell/curl yok.
ENTRYPOINT ["/opt/java/jre/bin/java", "-XX:MaxRAMPercentage=75.0", "-jar", "/app/app.jar"]
