# Build stage
FROM maven:3.9.5-eclipse-temurin-17 AS builder
WORKDIR /build

COPY pom.xml .
RUN mvn dependency:go-offline -B

COPY src/ src/
RUN mvn clean package -DskipTests -B && \
    echo "=== Built JAR ===" && ls -lh target/*.jar

# Runtime stage
FROM eclipse-temurin:17-jre
WORKDIR /app

RUN apt-get update && \
    apt-get install -y netcat-openbsd && \
    rm -rf /var/lib/apt/lists/*

COPY --from=builder /build/target/raft-grpc-1.0-SNAPSHOT.jar app.jar

EXPOSE 50051
ENV JAVA_TOOL_OPTIONS="-Djava.net.preferIPv4Stack=true"

ENTRYPOINT ["java", \
  "-Xms64m", "-Xmx256m", \
  "-XX:+UseContainerSupport", \
  "-Djava.net.preferIPv4Stack=true", \
  "-Djava.util.logging.SimpleFormatter.format=[%1$tT] %4$s %2$s: %5$s%n", \
  "-jar", "app.jar"]