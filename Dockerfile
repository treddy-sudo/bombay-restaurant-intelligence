FROM node:22-alpine AS frontend
WORKDIR /app/frontend
COPY frontend/package.json ./
RUN npm install --no-audit --no-fund
COPY frontend/ ./
RUN npm run build

FROM maven:3.9.9-eclipse-temurin-21 AS backend
WORKDIR /app
COPY pom.xml ./
RUN mvn -B -q -DskipTests dependency:go-offline
COPY src ./src
COPY --from=frontend /app/frontend/dist ./src/main/resources/static
RUN mvn -B test package

FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=backend /app/target/restaurant-intelligence-0.1.0.jar app.jar
ENV PORT=10000
EXPOSE 10000
ENTRYPOINT ["sh","-c","java -Dserver.port=${PORT} -jar app.jar"]
