# ContentCreator

Content workers for ElderEase. Spring Boot 3.5, Java 21.

## NewsSummaryWorker (Anthropic API)

**Off by default** (`CONTENTCREATOR_API_WORKER_ENABLED=true` turns it on; needs `ANTHROPIC_API_KEY`). On a schedule (default 06:00, 12:00 and 18:00 Vietnam time) the worker calls the **Anthropic API** (Messages API with
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

## NewsFileWorker (bulletin files)

Watches a folder (default `~/Downloads/newssummary`, change with `CONTENTCREATOR_NEWS_DIR`) every 30 seconds for new
`*.json` bulletin files. For each file it builds one news summary per section and inserts them into the same `news_summary` table as
**unpublished drafts** (country VN, language `vi`) for an admin to review and publish. A file that was read moves to
`processed/` inside the folder; one that cannot be read (bad JSON, larger than 2 MB) moves to `failed/`, with the reason in
the log. A file modified in the last 5 seconds is left for the next scan, symbolic links are never followed, and a
story whose headline and date already exist for Vietnam is skipped, so re-dropping a file creates no duplicates.

File format (as in `ban-tin-2026-10-20.json`):

```json
{"date": "2026-10-20",
 "sections": {
   "domestic": [{"title": "...", "summary": "...", "source": "VnExpress", "url": "..."}],
   "world":    [{"title": "...", "summary": "...", "source": "Al Jazeera", "url": "..."}],
   "stocks":   {"indices": [{"name": "VN-Index", "close": 1691.97}],
                "items": [{"title": "...", "summary": "...", "source": "CafeF"}]}
 }}
```

- **One draft per section.** The sample file gives 4 drafts: "🏠 Tin trong nước", "🌍 Tin thế giới",
  "📈 Tin chứng khoán (phiên gần nhất 20/10)" and "🔬 Tin khoa học & công nghệ" (a section name with no preset
  heading becomes "📰 Tin <name>"). A section is a list of stories, or an object with an `items` list (like `stocks`).
- The **headline** is the section heading; the **summary** has one paragraph per story, separated by a blank line, in a
  small markup the app renders as in the reference design: `**Title**: text [Source](https://link)` - bold lead-in,
  then the text, then the source as a tappable underlined link. A story with no valid http(s) link ends with
  "(Nguồn: Source)" instead. The characters `*`, `[` and `]` are removed from titles and texts so a story cannot
  break the formatting.
- The summary column holds 5,000 characters (ElderEase migration V38). Stories that would not fit are left out whole,
  never cut in the middle.
- `date` is the news date (today in Vietnam if missing or unreadable); for `stocks`, `session_date` is shown in the
  heading. The worker sets no other fields. The same headline and date is never inserted twice.
- Turn it off with `CONTENTCREATOR_FILE_WORKER_ENABLED=false`.

## Run locally

1. (Only for the Anthropic API worker) get an API key at https://console.anthropic.com (Settings → API Keys; add billing credits; web search must be enabled
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
