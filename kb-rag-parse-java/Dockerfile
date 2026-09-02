# Build stage. Dependencies are resolved in their own layer so a source-only
# change does not re-download the world on every rebuild.
FROM maven:3.9-eclipse-temurin-17 AS build

WORKDIR /build

COPY pom.xml .
RUN mvn -B -q dependency:go-offline

COPY src ./src
# Tests run in CI, not in the image build: a build that also runs the suite
# makes every deploy wait on it, and hides a red suite behind "the image built".
RUN mvn -B -q -DskipTests package

# Runtime stage. JRE only - the JDK and the whole Maven repository stay behind.
FROM eclipse-temurin:17-jre

WORKDIR /app

COPY --from=build /build/target/kb-rag-parse-java-*.jar app.jar

# Parsing is CPU-bound and untrusted-input-driven; running as a non-root user
# limits what a hypothetical parser escape could reach.
RUN useradd --system --create-home --uid 10001 parser
USER parser

# kb-rag-parse-java listens on 20001, per the M1-CONTRACTS.md §0 port assignment.
EXPOSE 20001

# No outbound network access is required or expected at runtime (requirement
# doc §4.2): the container's network policy should only allow inbound traffic
# from kb-rag-server.
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
