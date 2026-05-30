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
WORKDIR /app
COPY --from=extractor /app/extracted/BOOT-INF/ ./BOOT-INF/
COPY --from=extractor /app/extracted/META-INF/ ./META-INF/

# Free-tier RAM optimization (limits heap to 75% of container memory)
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75"

# Run the app using explicit classpath so java.class.path contains all deps
ENTRYPOINT ["java", "-cp", "BOOT-INF/classes:BOOT-INF/lib/*", "com.codeforger.CodeForgerApplication"]
