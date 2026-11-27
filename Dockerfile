# ==============================================================================
# Stage 1: Build Auction Server bằng Maven & JDK 17
# ==============================================================================
FROM maven:3.9-eclipse-temurin-17 AS builder

WORKDIR /build
COPY pom.xml .
RUN mvn -B -q dependency:go-offline || true

COPY src ./src
RUN mvn -B -DskipTests compile dependency:copy-dependencies \
    -DincludeScope=runtime \
    -DoutputDirectory=target/lib

# ==============================================================================
# Stage 2: Runtime gọn nhẹ chỉ chạy AuctionServer (TCP Socket + Cloud DB)
# ==============================================================================
FROM eclipse-temurin:17-jre-alpine

WORKDIR /app
COPY --from=builder /build/target/classes /app/classes
COPY --from=builder /build/target/lib /app/lib
COPY --from=builder /build/src/database/schema.sql /app/src/database/schema.sql

ENV PORT=8888
EXPOSE 8888

# Chạy Server ở chế độ Cloud 24/7: lắng nghe 0.0.0.0:$PORT, kết nối Cloud DB qua biến môi trường AUCTION_DB_URL / DATABASE_URL
ENTRYPOINT ["sh", "-c", "java -cp '/app/classes:/app/lib/*' com.auction.server.ServerApplication --bind 0.0.0.0 --port ${PORT:-8888} --no-discovery --no-registry"]
