FROM maven:3.9.9-eclipse-temurin-17

WORKDIR /app

# 1. Pre-fetch dependencies to leverage Docker layer caching
COPY pom.xml .
RUN mvn -B dependency:resolve dependency:resolve-plugins

# 2. Copy test sources, configuration and suite descriptors
COPY src ./src
COPY testng.xml .

# Ensure a config.properties exists on classpath for compilation/runtime if not mounted
RUN if [ ! -f src/test/resources/config.properties ]; then cp src/test/resources/config.properties.example src/test/resources/config.properties; fi

# 3. Compile tests at build time to verify integrity
RUN mvn -B -DskipTests test-compile

# 4. Default run: offline runtime verification test
CMD ["mvn", "test", "-Dtest=LocalRuntimeTest"]
