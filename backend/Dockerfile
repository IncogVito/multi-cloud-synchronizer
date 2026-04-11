# Stage 1: Build shadow (fat) jar
FROM gradle:8-jdk21 AS builder

WORKDIR /app

# Copy gradle files first for dependency caching
COPY settings.gradle.kts build.gradle.kts ./
COPY gradle ./gradle

# Download dependencies (cached layer)
RUN gradle dependencies --no-daemon || true

# Copy source code
COPY src ./src

# Build the shadow jar (skip tests — run them in CI before the image build)
RUN gradle shadowJar --no-daemon -x test

# Stage 2: Minimal JRE runtime
FROM eclipse-temurin:21-jre-jammy

WORKDIR /app

# Tools needed by disk/mount scripts and the healthcheck
RUN apt-get update && apt-get install -y --no-install-recommends \
    wget \
    util-linux \
    mount \
    imagemagick \
    libheif1 \
    openssh-client \
    && rm -rf /var/lib/apt/lists/*

# Copy the fat jar from the builder stage
COPY --from=builder /app/build/libs/*-all.jar /app/app.jar

EXPOSE 8080

ENTRYPOINT ["java", "-jar", "/app/app.jar"]
