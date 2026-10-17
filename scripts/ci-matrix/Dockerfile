FROM eclipse-temurin:25-jdk-noble AS jdk25
FROM eclipse-temurin:21-jdk-noble
COPY --from=jdk25 /opt/java/openjdk /opt/java/jdk25
RUN apt-get update \
    && apt-get install -y --no-install-recommends python3 curl unzip gnupg \
    && rm -rf /var/lib/apt/lists/*
ENV JAVA_HOME_21_X64=/opt/java/openjdk \
    GRADLE_USER_HOME=/cache \
    HOME=/tmp/ci-home
ENTRYPOINT ["python3", "/source/scripts/ci-matrix/run.py"]
