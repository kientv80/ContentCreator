# ContentCreator

Content workers for ElderEase. Spring Boot 3.5, Java 21.

## NewsSummaryWorker

On a schedule (default 06:00, 12:00 and 18:00 Vietnam time) the worker calls the **Anthropic API** (Messages API with
the web search tool) to collect today's Vietnamese news and write a short Vietnamese summary of each story. It
inserts them **directly into ElderEase's existing `news_summary` table** as **unpublished drafts**:
`published = FALSE`, `country_code = 'VN'`, `language = 'vi'`, no image or audio. Nothing reaches elders until an
admin opens the draft on the admin site (News section), reviews or edits it, and presses **Publish**; a draft an
admin does not want can be deleted there.

- Each summary ends with its source, e.g. "(Nguồn: VnExpress)", so the admin can check it (the table has no link column).
- A summary whose headline and news date already exist for Vietnam (in any state) is skipped, so a repeated run does
  not create duplicates. A draft the admin deleted can come back on a later run.
- Headline is cut to 120 and summary to 1,000 characters, the table's limits.
- A failed run (no key, API error, unreadable answer) is logged and the next schedule tries again; nothing is stored.
- No HTTP endpoint is exposed except `/actuator/health`.

Settings are in `application.yml` under `contentcreator.news.worker` (cron, `run-on-startup`, `max-items`) and
`contentcreator.claude` (model, web-search limit, time-out).

## Run locally

1. Get an API key at https://console.anthropic.com (Settings → API Keys; add billing credits; web search must be enabled
   for the organization). Never commit it.
2. Create a MySQL user that can only read and add to that one table:

```sql
CREATE USER 'contentcreator'@'%' IDENTIFIED BY '<password>';
GRANT SELECT, INSERT ON elderfriend.news_summary TO 'contentcreator'@'%';
```

3. Start it:

```bash
export ANTHROPIC_API_KEY=sk-ant-...
export CONTENTCREATOR_DB_PASSWORD=<password>    # user defaults to `contentcreator` (CONTENTCREATOR_DB_USER)
export CONTENTCREATOR_RUN_ON_STARTUP=true       # optional: collect once right now
mvn -o spring-boot:run                          # port 8110
```

The database defaults to `localhost:3307/elderfriend` (override with `CONTENTCREATOR_DB_URL`). The ElderEase backend
must already have created the table (migration V20); ContentCreator never changes the schema.

Cost: each run uses one model call plus up to `web-search-max-uses` (8) searches; three runs a day by default.
