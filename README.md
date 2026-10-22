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
the log. A file modified in the last 5 seconds is left for the next scan, symbolic links are never followed (files are opened without following links, a file with more than one hard link is
refused and moved to `failed/`, the size limit is enforced while reading, and the scan stops if `processed/` or `failed/` is a
symbolic link or not a directory), and a
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
  then the text, then the source as a tappable underlined link. A story with no safe link ends with
  "(Nguồn: Source)" as plain text instead. The characters `*`, `[` and `]` are removed from titles and texts (also for
  drafts from the Anthropic API worker) so a story cannot break the formatting or plant links.
- **Link rules** (`SafeLinks`): the URL must be `http` or `https`, at most 500 characters, with a host of ASCII letters,
  digits, hyphens and at least one dot. It is rejected when it has user info (`@`), a raw IPv4/IPv6 address, a `xn--`
  (punycode) or non-ASCII host, a port that is not digits, backslashes, spaces, control, bidi-override or zero-width
  characters, or any of `* [ ] < > "`. When the source label looks like a domain (has a dot, no spaces, e.g.
  `vnexpress.net`) it must equal the link's host or be a parent domain of it (`www.` ignored), otherwise the link is dropped.
  A `)` in a URL is written as `%29`.
- The summary column holds 8,000 characters (ElderEase migration V41). Stories that would not fit are left out whole,
  never cut in the middle.
- **Byline time** (FR-321): each story may have a `published` value; its line then ends with
  `[Source](https://link) · HH:mm dd/MM` (or `(Nguồn: Source) · HH:mm dd/MM`), in Vietnam time (Asia/Ho_Chi_Minh), e.g.
  `[Dân Việt](https://…) · 15:47 20/10`. The leading `yyyy-MM-dd[Thh:mm[:ss]][Z|+hh:mm|+hhmm]` is read (a space may
  replace the `T`) and anything after it is ignored; a time with an offset is converted to Vietnam time, one without
  is taken as Vietnam time. A date only, or a midnight `00:00` placeholder, shows just `dd/MM`. A missing, blank,
  non-text or unreadable value (e.g. month 13, hour 25) adds nothing. A story with no source gets ` · time` after its
  text. The time counts toward the 8,000 characters.
- `date` is the news date (today in Vietnam if missing or unreadable); for `stocks`, `session_date` is shown in the
  heading. The worker sets no other fields. The same headline and date is never inserted twice.
- The **category** is server-driven (ElderEase migration **V42**, table `news_category`; `news_summary.category` is a
  foreign key to it). The section name is turned into a code (upper-cased, every character other than A-Z and 0-9 becomes
  `_`, e.g. `science_tech` -> `SCIENCE_TECH`) and looked up with `SELECT ... FROM news_category WHERE code = ? AND active = TRUE`
  (once per section, nothing cached, so a category an admin adds is used on the next scan). If it is found, that is the
  draft's category; otherwise (unknown, inactive, or the lookup fails) the draft is filed as `OTHER` and the admin can
  create the category later and move the draft. Headings: `domestic`, `world`, `stocks`, `science_tech` keep their fixed
  headings; any other section whose category exists is headed `<emoji> Tin <name_vi in lower case>` (e.g. `🩺 Tin sức khỏe`);
  an unknown section is headed `📰 Tin <name>`. Drafts from the Anthropic API worker are always `OTHER`.
  **Apply ElderEase V40 and V42 before running this version.** The duplicate check is unchanged.
- Turn it off with `CONTENTCREATOR_FILE_WORKER_ENABLED=false`.

## ShareThoughtWorker (daily letters)

Watches a folder (default `${user.home}/Documents/Elderfriend Posts`, env `CONTENTCREATOR_POSTS_DIR`; poll 60 s, a file
must be unmodified for 5 s, at most 200,000 bytes) for letters named `yyyy-MM-dd-vi.md` and `yyyy-MM-dd-en.md`. Each
file holds several posts, each starting with a `## Bài N: ...` / `## Post N: ...` heading; every post becomes ONE Shared Thought row in ElderEase's `poems` table: `privacy = 'PUBLIC'`, `approval_status = 'PENDING'`
(an admin approves it), `local_date` = the date in the file name, no voice.

- **Writer**: `-vi` -> user `elderfriend_ai_vn`, `-en` -> user `elderfriend_ai_us` (properties
  `contentcreator.thoughts.accounts.vi` / `.en`), found with `SELECT id FROM users WHERE username = ?`. If the account
  does not exist the file stays in the folder and one WARN is logged per scan; it is picked up once the account exists.
- **Posts**: `LetterText.splitPosts` splits a file at each line starting with `## `; the `# title` and rules before the first `## ` are dropped, empty sections are dropped; a file with no `## ` is one post. Posts are inserted in reverse order, `created_at` one second apart, so Post 1 is the newest and listed first.
- **Text**: each post's Markdown is turned into plain text (`LetterText`): BOM, control and bidi characters removed; `---` lines
  dropped; headings keep their text without `#`; `**`, `*` and backticks removed; `- ` / `* ` bullets become `• `;
  `[text](url)` becomes `text`; 3 or more blank lines become one blank line. Each post must be 1 to 6,000 characters;
  a longer post is cut after the last whole paragraph that fits (WARN logged), never in the middle of one.
- **Files**: every post stored or already stored -> `processed/` (log `thought_done file=... posts=N stored=M`); unreadable, too big, hard-linked or empty
  after cleaning -> `failed/`; any other `.md` name (plain `2026-10-21.md`, `-fr.md`, an impossible date) ->
  `skipped/`. Same hardening as the bulletin worker: no symbolic links, one hard link only, read through one handle
  capped at the size limit, symlinked or non-directory `processed/`, `failed/`, `skipped/` stop the scan, subfolders
  and non-`.md` files are ignored. A database error leaves the file for the next scan (posts already inserted are skipped on the retry).
- **Duplicates**: a post is skipped when the same writer has a `poems` row on that `local_date`, in any state (also deleted), whose `content_text` starts with the post's heading line (LIKE wildcards escaped): at most one thought per post heading per account per day.
- Turn it off with `CONTENTCREATOR_THOUGHT_WORKER_ENABLED=false`.
- **Database**: apply ElderEase migration **V43** (`poems.content_text` becomes 6000 characters) first, and add:

```sql
GRANT SELECT, INSERT ON elderfriend.poems TO 'contentcreator'@'%';
GRANT SELECT (id, username) ON elderfriend.users TO 'contentcreator'@'%';
```

## Run locally

1. (Only for the Anthropic API worker) get an API key at https://console.anthropic.com (Settings → API Keys; add billing credits; web search must be enabled
   for the organization). Never commit it.
2. Create a MySQL user that can only read and add to that one table:

```sql
CREATE USER 'contentcreator'@'%' IDENTIFIED BY '<password>';
GRANT SELECT, INSERT ON elderfriend.news_summary TO 'contentcreator'@'%';
GRANT SELECT ON elderfriend.news_category TO 'contentcreator'@'%';
```

The second grant (read-only) is needed for the category lookup (V42); without it every draft is filed as `OTHER`.

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
