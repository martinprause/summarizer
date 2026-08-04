# =====================================================================
# Stage 1 — Build: Maven + Node bauen die App inklusive Vaadin-Frontend
# =====================================================================
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /build

# Dependencies zuerst (eigener Layer -> Cache bleibt bei Code-Änderungen erhalten)
COPY pom.xml .
RUN mvn -B -q dependency:go-offline

# npm-Manifeste mitkopieren — sonst erzeugt Vaadin eine frische package.json
# ohne devDependencies (sass-embedded fuer frappe-gantt) und der Vite-Build bricht
COPY package.json package-lock.json tsconfig.json types.d.ts vite.config.ts ./

# Quellcode + Frontend-Ressourcen
COPY src src

# Produktions-Build: Vaadin baut das optimierte Frontend-Bundle ins Jar
# (ohne -Dvaadin.productionMode bleibt das Jar im Dev-Modus und braucht zur
#  Laufzeit den Quellordner — im Runtime-Image nicht vorhanden)
RUN mvn -B -q -DskipTests -Pproduction package

# =====================================================================
# Stage 2 — Runtime: schlanke JRE, nur das fertige Jar
# =====================================================================
FROM eclipse-temurin:21-jre
WORKDIR /app

# yt-dlp (eigenständiges Binary) + ffmpeg für YouTube-Transkripte
RUN apt-get update \
    && apt-get install -y --no-install-recommends ffmpeg curl ca-certificates \
    && rm -rf /var/lib/apt/lists/* \
    && curl -fsSL https://github.com/yt-dlp/yt-dlp/releases/latest/download/yt-dlp_linux \
         -o /usr/local/bin/yt-dlp \
    && chmod +x /usr/local/bin/yt-dlp

# Nicht als root laufen
RUN groupadd --system summarizer && useradd --system --gid summarizer summarizer \
    && mkdir -p /data/files && chown -R summarizer:summarizer /data

COPY --from=build --chown=summarizer:summarizer /build/target/summarizer-*.jar app.jar

USER summarizer
EXPOSE 8080
ENV JAVA_OPTS="-XX:MaxRAMPercentage=75"

HEALTHCHECK --interval=30s --timeout=5s --start-period=90s --retries=3 \
    CMD ["sh", "-c", "curl -fsS http://localhost:8080/login > /dev/null || exit 1"]

ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar app.jar"]
