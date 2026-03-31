# Stage 1: Build native image with GraalVM
FROM gradle:8-jdk21 AS builder

WORKDIR /app

# Copy gradle files first for dependency caching
COPY settings.gradle.kts build.gradle.kts ./
COPY gradle ./gradle

# Download dependencies (cached layer)
RUN gradle dependencies --no-daemon || true

# Copy source code
COPY src ./src

# Build native image (this takes a while)
RUN gradle nativeCompile --no-daemon -Pmicronaut.environments=docker

# Stage 2: Minimal runtime image
FROM gcr.io/distroless/base-debian12

WORKDIR /app

# Copy the native binary
COPY --from=builder /app/build/native/nativeCompile/cloud-synchronizer-backend .

EXPOSE 8080

ENTRYPOINT ["./cloud-synchronizer-backend"]
