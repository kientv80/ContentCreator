# ContentCreator

Background workers that create content for ElderEase. Spring Boot 3.5, Java 21, MySQL.

## NewsSummaryWorker

Collects today's Vietnamese news with Claude (Messages API + web search tool), has Claude write a short
Vietnamese summary of each story, and stores new ones in the `news_summary` table (duplicates are skipped by
link; rows older than `retention-days` are removed).

- Schedule: `contentcreator.news.worker.cron` (default every 3 hours, 06:00-21:00 Vietnam time).
  Set `run-on-startup: true` to also run once at start.
- The News Summary screen loads `GET /api/v1/news-summaries?page=0&size=20` (newest first, size capped at 50).
  Fields: id, title, summary, category, sourceName, sourceUrl, language, publishedAt, collectedAt.

## Run locally

```bash
export ANTHROPIC_API_KEY=sk-ant-...        # never commit it
export CONTENTCREATOR_DB_PASSWORD=...      # MySQL user is root by default (CONTENTCREATOR_DB_USER)
mvn -o spring-boot:run                      # port 8110; the database `contentcreator` is created if missing
```

MySQL defaults to `localhost:3307` (the ElderEase local server); override with `CONTENTCREATOR_DB_URL`.
Model and web-search settings are under `contentcreator.claude` in `application.yml`.

## Not done yet

- ElderEase app: the News Summary screen that calls this API (and the proxy, if the app should reach it
  through the ElderEase backend instead of directly).
- The endpoint has no authentication; put it behind the ElderEase backend or add auth before exposing it.
