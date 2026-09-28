# ClinicIT web app. Build: docker build -t clinicit-web --build-arg NEXT_PUBLIC_API_BASE_URL=https://api.example.com frontend
# NEXT_PUBLIC_API_BASE_URL is compiled into the browser code, so it is a build argument.

FROM node:22-alpine AS build
WORKDIR /app
COPY package.json package-lock.json ./
RUN npm ci
COPY . .
ARG NEXT_PUBLIC_API_BASE_URL=http://localhost:8080
ENV NEXT_PUBLIC_API_BASE_URL=$NEXT_PUBLIC_API_BASE_URL NEXT_TELEMETRY_DISABLED=1
RUN npm run build && npm prune --omit=dev

FROM node:22-alpine
WORKDIR /app
ARG NEXT_PUBLIC_API_BASE_URL=http://localhost:8080
# Read again at start by next.config.ts for the Content-Security-Policy.
ENV NODE_ENV=production NEXT_TELEMETRY_DISABLED=1 NEXT_PUBLIC_API_BASE_URL=$NEXT_PUBLIC_API_BASE_URL PORT=3000
COPY --from=build --chown=node:node /app/package.json /app/next.config.ts ./
COPY --from=build --chown=node:node /app/node_modules ./node_modules
COPY --from=build --chown=node:node /app/.next ./.next
USER node
EXPOSE 3000
CMD ["node_modules/.bin/next", "start"]
