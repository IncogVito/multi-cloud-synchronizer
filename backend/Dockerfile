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
RUN sed -i 's|URIs: http://|URIs: https://|g' /etc/apt/sources.list.d/ubuntu.sources 2>/dev/null; \
    sed -i 's|^deb http://|deb https://|g' /etc/apt/sources.list 2>/dev/null; \
    sed -i '/^Components:/ { /universe/! s/$/ universe/ }' /etc/apt/sources.list.d/ubuntu.sources 2>/dev/null; \
    sed -i 's/^\(deb https:\/\/.*\) main$/\1 main universe/' /etc/apt/sources.list 2>/dev/null; \
    true
RUN apt-get update -o Acquire::Retries=3 && apt-get install -y --no-install-recommends \
    wget \
    util-linux \
    mount \
    libvips-tools \
    libheif1 \
    ffmpeg \
    openssh-client \
    && rm -rf /var/lib/apt/lists/*

# Copy the fat jar from the builder stage
COPY --from=builder /app/build/libs/*-all.jar /app/app.jar

EXPOSE 8080

ENTRYPOINT ["java", "-jar", "/app/app.jar"]
