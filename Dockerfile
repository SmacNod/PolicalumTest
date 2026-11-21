# ==========================================
# Etapa 1: Construcción (Build con Maven)
# ==========================================
FROM maven:3.9.6-eclipse-temurin-17 AS build
WORKDIR /app

COPY pom.xml .
COPY src ./src

# Compilación limpia omitiendo tests
RUN mvn clean package -Dmaven.test.skip=true

# ==========================================
# Etapa 2: Ejecución ligera (Solo Java)
# ==========================================
FROM eclipse-temurin:17-jre
WORKDIR /app

# Copiamos el JAR generado desde la primera etapa
COPY --from=build /app/target/*.jar app.jar

EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]