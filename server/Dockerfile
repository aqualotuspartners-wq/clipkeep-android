FROM node:22-alpine
WORKDIR /app
COPY server.mjs rapidapi.mjs ./
RUN mkdir -p /content/media \
    && printf '[]\n' > /content/catalog.json \
    && chown -R node:node /content
USER node
ENV HOST=0.0.0.0 PORT=8080 CATALOG=/content/catalog.json MEDIA_DIRECTORY=/content/media
EXPOSE 8080
CMD ["node", "server.mjs"]
