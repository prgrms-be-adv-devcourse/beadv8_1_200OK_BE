# ---- 빌드 단계 ----
FROM eclipse-temurin:25-jdk AS build
WORKDIR /workspace

# 의존성 캐시를 위해 빌드 스크립트를 먼저 복사
COPY gradlew settings.gradle.kts ./
COPY gradle ./gradle
COPY build-logic ./build-logic
COPY monolith/build.gradle.kts ./monolith/
RUN ./gradlew --no-daemon dependencies > /dev/null

COPY monolith/src ./monolith/src
# 테스트는 이미지 빌드에서 제외
RUN ./gradlew --no-daemon :monolith:bootJar -x test \
    && rm -f monolith/build/libs/*-plain.jar

# ---- 실행 단계 ----
FROM eclipse-temurin:25-jre-alpine
WORKDIR /app

RUN addgroup -S appgroup && adduser -S -u 1001 -G appgroup appuser
COPY --from=build /workspace/monolith/build/libs/*.jar app.jar
USER appuser

# 프로파일은 실행할 때 SPRING_PROFILES_ACTIVE=prod 로 지정 (미지정 시 dev)
EXPOSE 8080
# JVM 시간대를 팀 기준(Asia/Seoul)으로 고정 (#93)
# - eclipse-temurin 이미지는 기본 시간대가 UTC라서, 그대로 두면 LocalDateTime.now()와 createdAt이 UTC로 계산된다
#   예) 정산 스케줄러는 02:00 KST에 실행되는데 실행일은 UTC 기준 전날로 잡혀 하루 앞당겨짐
# - .env 의 APP_TIME_ZONE 은 JDBC·Hibernate·Jackson에만 적용되고 JVM 시간대는 바꾸지 못하므로 실행 옵션으로 지정한다
# - CI 테스트도 build-logic의 ok.java-conventions 플러그인에서 같은 값으로 고정한다. 둘 중 하나를 바꾸면 다른 쪽도 함께 바꾼다
ENTRYPOINT ["java", "-Duser.timezone=Asia/Seoul", "-jar", "app.jar"]
