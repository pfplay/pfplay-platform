FROM eclipse-temurin:21-jre-alpine
RUN apk add --no-cache tzdata
ENV TZ=Asia/Seoul
WORKDIR /opt/api
COPY app/build/libs/*.jar app.jar
ENTRYPOINT ["java", "-jar", "app.jar"]
