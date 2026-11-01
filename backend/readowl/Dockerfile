# Versões e digests fixos: o mesmo Dockerfile gera a mesma base hoje e daqui a um ano. O Dependabot propõe as atualizações.
FROM eclipse-temurin:25.0.5_8-jdk-noble@sha256:95e350ad1eb738f943e141640f7e3d797e483359a3b33c3864255bfd1ad71f8a AS build
WORKDIR /src
COPY .mvn/ .mvn/
COPY mvnw pom.xml ./
# Dependências em camada própria: mudar o código não baixa tudo de novo.
RUN sh ./mvnw --batch-mode --no-transfer-progress dependency:go-offline
COPY src/ src/
# Os testes precisam de banco e rodam no pipeline, antes da imagem.
RUN sh ./mvnw --batch-mode --no-transfer-progress package -DskipTests \
    && cp target/readowl-*.jar app.jar \
    && java -Djarmode=tools -jar app.jar extract --layers --destination /extracted

FROM eclipse-temurin:25.0.5_8-jre-noble@sha256:a98128a57de944a72bd31fbc79b898edd5a5864d3652071d5bbd2160ab7d7dce
RUN groupadd --system --gid 10001 readowl \
    && useradd --system --uid 10001 --gid 10001 --no-create-home --shell /usr/sbin/nologin readowl
WORKDIR /app
COPY --from=build /extracted/dependencies/ ./
COPY --from=build /extracted/spring-boot-loader/ ./
COPY --from=build /extracted/snapshot-dependencies/ ./
COPY --from=build /extracted/application/ ./
USER 10001:10001
ENV SPRING_PROFILES_ACTIVE=prod
EXPOSE 8080 8081
# Heap proporcional ao limite do contêiner. Sem memória, encerra e reinicia em vez de seguir instável, e sem heap dump,
# que conteria sessões e senhas em memória.
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=70", "-XX:+ExitOnOutOfMemoryError", "-jar", "app.jar"]
