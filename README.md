# ContentCreator

Content workers for ElderEase. Spring Boot 3.5, Java 21, MySQL.

## NewsSummaryWorker

The Claude coworker (claude.ai) writes the Vietnamese news summaries and **publishes them through this API**;
the worker inserts the new ones into the `news_summary` table (a story whose link is already stored is skipped)
and removes stories older than `contentcreator.news.retention-days` (30) every night at 03:30 Vietnam time.
The ElderEase backend reads them for the News Summary page (VN users).

### Publish API (for the coworker)

```
POST /api/v1/news-summaries
X-API-Key: <CONTENTCREATOR_INGEST_KEY>
Content-Type: application/json

{"items": [
  {"title": "Vietnamese headline",
   "summary": "2-3 sentence Vietnamese summary",
   "category": "health",                       // optional
   "source": "VnExpress",                      // optional
   "url": "https://vnexpress.net/...",         // required, http(s); the de-duplication key
   "published": "2026-10-20"}                  // optional ISO date or date-time
]}
```

`201 {"received": n, "stored": m}`; `401` for a missing or wrong key (also when no key is configured);
`400` for an invalid body. At most 50 items per call.

### Read API (for ElderEase)

`GET /api/v1/news-summaries?page=0&size=20` - newest first, size capped at 50. No authentication: keep it
reachable only by the ElderEase backend. Only the publish call needs to be public.

## Run locally

```bash
export CONTENTCREATOR_INGEST_KEY=$(openssl rand -hex 32)   # give the same value to the coworker; never commit it
export CONTENTCREATOR_DB_PASSWORD=...                       # MySQL user root by default (CONTENTCREATOR_DB_USER)
mvn -o spring-boot:run                                      # port 8110; database `contentcreator` is created if missing
```

MySQL defaults to `localhost:3307`; override with `CONTENTCREATOR_DB_URL`.

## Going public

The coworker runs in the cloud, so the publish URL must be reachable from the internet over **HTTPS** (reverse proxy
or tunnel in front of port 8110). Expose only `POST /api/v1/news-summaries` publicly; keep `GET` and `/actuator`
internal (proxy rule), because the read API has no authentication.
