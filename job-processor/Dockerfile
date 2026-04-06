# Stage 1: Build Go analyzer binary
FROM golang:1.22-alpine AS go-builder
WORKDIR /build
COPY analyzer/go.mod analyzer/main.go ./
RUN CGO_ENABLED=0 GOOS=linux go build -o analyzer main.go

# Stage 2: Node.js runtime with Go binary
FROM node:20-alpine
WORKDIR /app

COPY package.json .
RUN npm install --production

COPY processor.js .
COPY --from=go-builder /build/analyzer ./analyzer
RUN chmod +x ./analyzer

CMD ["node", "processor.js"]
