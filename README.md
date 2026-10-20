# ContentCreator

Content workers for ElderEase. Spring Boot 3.5, Java 21.

## NewsSummaryWorker

Your Claude coworker (claude.ai) writes Vietnamese news summaries and submits them to this service. The
worker inserts them **directly into ElderEase's existing `news_summary` table** (the one the admin site
manages) as **unpublished drafts**: `published = FALSE`, `country_code = 'VN'`, `language = 'vi'`, no image
or audio. Nothing reaches elders until an admin opens the draft on the admin site (News section), reviews or
edits it, and presses **Publish**. A draft an admin does not want can be deleted there.

A summary whose headline and news date already exist for Vietnam (in any state) is skipped, so a coworker
that repeats itself does not create duplicates. (A draft the admin deleted can be submitted again.)

### Submit API (for the coworker)

```
POST /api/v1/news-summaries
X-API-Key: <CONTENTCREATOR_INGEST_KEY>
Content-Type: application/json

{"items": [
  {"headline": "Vietnamese headline (max 120 chars)",
   "summary": "Vietnamese summary (max 1,000 chars). End with the source, e.g. (Nguồn: VnExpress)",
   "newsDate": "2026-10-20"}                  // optional ISO date or date-time; default today (Vietnam)
]}
```

`201 {"received": n, "stored": m}`; `401` for a missing or wrong key (also when no key is configured);
`400` for an invalid body. At most 50 items per call. The table has no column for the article link, so ask the
coworker to name the source inside the summary for the admin to check.

## Run locally

Create a MySQL user that can only read and add to that one table:

```sql
CREATE USER 'contentcreator'@'%' IDENTIFIED BY '<password>';
GRANT SELECT, INSERT ON elderfriend.news_summary TO 'contentcreator'@'%';
```

```bash
export CONTENTCREATOR_INGEST_KEY=$(openssl rand -hex 32)   # give the same value to the coworker; never commit it
export CONTENTCREATOR_DB_PASSWORD=<password>               # user defaults to `contentcreator` (CONTENTCREATOR_DB_USER)
mvn -o spring-boot:run                                      # port 8110
```

The database defaults to `localhost:3307/elderfriend` (override with `CONTENTCREATOR_DB_URL`). The ElderEase
backend must already have created the table (migration V20); ContentCreator never changes the schema.

## Going public

The coworker runs in the cloud, so the submit URL must be reachable over **HTTPS** (reverse proxy or tunnel in
front of port 8110). Expose only `POST /api/v1/news-summaries`; keep `/actuator` internal.
