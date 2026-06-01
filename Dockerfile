# Stage 1: Build the fat jar
FROM eclipse-temurin:21-jdk AS builder
WORKDIR /app
COPY backend/mvnw .
COPY backend/.mvn .mvn
COPY backend/pom.xml .
COPY backend/src src
RUN ./mvnw clean package -DskipTests

# Stage 2: Extract using standard jar tool
# Replaces deprecated layertools and yields a clean BOOT-INF structure
FROM builder AS extractor
WORKDIR /app/extracted
RUN jar -xf /app/target/code-forger-backend-*.jar

# Stage 3: Run with exploded classpath
FROM eclipse-temurin:21-jdk

# Hugging Face Spaces runs as UID 1000
RUN useradd -m -u 1000 user
USER user
ENV HOME=/home/user \
    PATH=/home/user/.local/bin:$PATH \
    PORT=7860

WORKDIR $HOME/app

# Copy files and ensure they are owned by the non-root user
COPY --from=extractor --chown=user:user /app/extracted/BOOT-INF/ ./BOOT-INF/
COPY --from=extractor --chown=user:user /app/extracted/META-INF/ ./META-INF/

# Free-tier RAM optimization (limits heap to 75% of container memory)
# 16GB RAM on HF allows for generous heap allocation
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75"

# Run the app using explicit classpath so java.class.path contains all deps
ENTRYPOINT ["java", "-cp", "BOOT-INF/classes:BOOT-INF/lib/*", "com.codeforger.CodeForgerApplication"]
