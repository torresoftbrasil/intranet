FROM amazoncorretto:25-al2023 AS build
RUN yum install -y tar gzip && yum clean all
WORKDIR /build
COPY apps/api/ ./
RUN chmod +x mvnw && ./mvnw -B -DskipTests package
FROM amazoncorretto:25-al2023
WORKDIR /app
COPY --from=build /build/target/intranet-api-*.jar /app/api.jar
RUN mkdir -p /app/uploads && chown -R 10001:10001 /app
USER 10001:10001
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/api.jar"]
