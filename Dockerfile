# ---- 빌드 단계 ----
FROM eclipse-temurin:25-jdk AS build
WORKDIR /workspace

# 의존성 캐시를 위해 빌드 스크립트를 먼저 복사
COPY gradlew settings.gradle.kts build.gradle.kts ./
COPY gradle ./gradle
RUN ./gradlew --no-daemon dependencies > /dev/null

COPY src ./src
# 테스트는 이미지 빌드에서 제외
RUN ./gradlew --no-daemon bootJar -x test \
    && rm -f build/libs/*-plain.jar

# ---- 실행 단계 ----
FROM eclipse-temurin:25-jre-alpine
WORKDIR /app

RUN addgroup -S appgroup && adduser -S -u 1001 -G appgroup appuser
COPY --from=build /workspace/build/libs/*.jar app.jar
USER appuser

# 프로파일은 실행할 때 SPRING_PROFILES_ACTIVE=prod 로 지정 (미지정 시 dev)
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
