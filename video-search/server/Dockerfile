FROM eclipse-temurin:21-jre-jammy
RUN apt-get update && apt-get install -y --no-install-recommends ffmpeg ca-certificates tesseract-ocr tesseract-ocr-chi-sim && rm -rf /var/lib/apt/lists/*
WORKDIR /app
COPY target/video-search-0.1.0.jar /app/server.jar
EXPOSE 8090
ENTRYPOINT ["java", "-jar", "/app/server.jar"]
