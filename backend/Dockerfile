# Stage 1: Build the fat jar
FROM eclipse-temurin:21-jdk AS builder
WORKDIR /app
# We assume the build context is the project root (.)
COPY backend/mvnw .
COPY backend/.mvn .mvn
COPY backend/pom.xml .
COPY backend/src src
RUN ./mvnw clean package -DskipTests

# Stage 2: Extract the Spring Boot layered jar
FROM builder AS extractor
WORKDIR /app
RUN java -Djarmode=layertools -jar target/code-forger-backend-*.jar extract

# Stage 3: Run with exploded classpath
FROM eclipse-temurin:21-jdk
WORKDIR /app
COPY --from=extractor /app/dependencies/ ./
COPY --from=extractor /app/spring-boot-loader/ ./
COPY --from=extractor /app/snapshot-dependencies/ ./
COPY --from=extractor /app/application/ ./

# Run the app using explicit classpath so java.class.path contains all deps
ENTRYPOINT ["java", "-cp", "BOOT-INF/classes:BOOT-INF/lib/*", "com.codeforger.CodeForgerApplication"]
