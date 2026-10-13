# Roadmap

Planned work, with the reasoning behind it. The numbers come from measurements
against the real collection (as of 2026-10-12: 15,674 entries, 13,749 distinct
printings, 31,721 cards).

Everything under **Completed** is live; those sections stay because they record
why things are built the way they are. Beyond them, the git history has the
rest: `/api/dashboard` aggregates server-side (2.4 MB → 1.8 KB),
`/api/collection` and `/api/sets` answer conditional requests with a 304, and
image URLs are derived in the browser from the Scryfall ID instead of being
transferred (14.8 → 10.0 MB).

---

# Completed

The sections below are live. They stay for the reasoning they record.

---

## 1. Move the search into the backend — done

> Implemented in `backend/cards.go` and `app/js/grid.js`. The card grid no
> longer loads the collection; the initial payload dropped from 2.36 MB to
> roughly 46 KB. `oracleText` is gone from `/api/collection` (2.36 → 1.32 MB
> gzip), which now only `editions.js` and `deck-checker.js` still use.
>
> Followed up: `/api/prints` and `/api/reserved` also return nothing but the
> Scryfall ID instead of ready-made image URLs, so they go through the mirror
> as well – they were the last two endpoints bypassing it.

### Why

At 2.24 MB the Oracle text is the last big item in `/api/collection`. It cannot
simply be dropped, because `grid.js` searches it:

```js
const hay = (c.name + ' ' + (sc.oracleText || '') + ' ' +
             String(sc.manaCost || '').replace(/[{}\/]/g, '')).toLowerCase();
```

The real gain is bigger than those 2.24 MB: with the search in the backend,
`index.html` stops loading the collection at all and fetches one page of
results instead.

### Endpoint

`GET /api/cards` with exactly the capabilities the interface has today:

| Parameter | Corresponds to |
| --- | --- |
| `q` | Search field: name + Oracle text + mana cost without `{}` and `/`, multiple terms ANDed |
| `set`, `rarity`, `color`, `foil`, `binder`, `type`, `subtype` | The seven filter dropdowns |
| `sort` | Name, price, quantity, added, edition |
| `cursor`, `limit` | Keyset paging, matching the incremental rendering from `f5f3632` |

The response already contains **grouped** rows – one entry per printing with
languages, total quantity, price and a representative, i.e. the same grouping
`grid.js` builds in the browser today – plus `total` and `totalGroups` for the
counters above the grid.

### Steps

1. Grouping and filters as SQL, starting with `LIKE`. Measure against 110,000
   catalog rows; only if that is not enough, FTS5 (included in
   `modernc.org/sqlite`) over name, Oracle text and type line.
2. A normalised column for the mana cost (`{1}{R}` → `1r`), otherwise every
   query runs a `replace()` across all rows.
3. Move `grid.js` onto the endpoint: debounce the input (250 ms), a filter
   change is one query, scrolling fetches the next cursor.
4. Only then remove `oracleText` from `/api/collection` and bump
   `payloadVersion` in `httpcache.go`.

### Safety net

Same as for the dashboard: run today's JS filter logic as a reference over the
real data and diff the result lists of around 30 parameter combinations against
the SQL variant. That is where the subtleties show up – case sensitivity,
umlauts, empty fields, `//` names on double-faced cards.

### The price

Filtering is instant today because everything is local. Afterwards every filter
change costs a round trip, measured at **~60 ms** on an open connection. In
exchange the initial load drops from several seconds to milliseconds, and on a
phone the memory for 15,000 card objects disappears.

Effort: backend ~300 lines, `grid.js` ~200 changed lines, plus the comparison
run.

---

## 2. Mirror images and symbols — done

> Implemented in `backend/images.go`, `nginx/default.conf` and `shared.js`.
> The default is `IMAGE_MIRROR=false`; when enabled the sync runs daily at
> `IMAGE_SYNC_HOUR`. `/api/sets/{code}/cards` has returned IDs instead of image
> URLs ever since, and the editions overlay uses the same flip as the card
> dialog.

### Storage

Its own volume `mtg-images:/images`, **not** in the database volume – otherwise
1.6 GB end up in every backup. Structure as at Scryfall:

```
/images/cards/<size>/<face>/<1>/<2>/<id>.jpg
/images/sets/<code>.svg
/images/symbols/<code>.svg
```

Size, calculated for the current collection: 13,749 printings × (~100 KB
`normal` + ~20 KB `small`) ≈ **1.6 GB**. Plus 1,056 set symbols and ~90 mana
symbols as SVG, under 10 MB together. The 80 back faces are negligible.

### Serving: local first, Scryfall otherwise

An existence check per image in the browser would mean one extra request per
card – costing more than the mirror saves. The decision is therefore made per
file in nginx, without the interface noticing:

```nginx
location /img/ {
    root /srv/images;
    try_files $uri @scryfall;   # mirrored locally -> serve directly
    expires 30d;
}
location @scryfall {            # otherwise transparently from Scryfall
    proxy_pass https://cards.scryfall.io;
    proxy_cache images;
}
```

The interface always asks for `/img/...`. The **switch** lives in `/api/config`
(`imageMirror: true|false`) and only controls which base URL
`scryfallImageURL()` builds in `shared.js` – at `false` everything goes
straight to Scryfall as before. That makes the mirror switchable off at any
time, without data loss and without a deploy.

### Background sync

Its own scheduler next to sync, import and backup.

| Variable | Default | Purpose |
| --- | --- | --- |
| `IMAGE_MIRROR` | `false` | The switch; off = everything as before |
| `IMAGE_SYNC_HOUR` | `3` | Start time, once per day |
| `IMAGE_SIZES` | `small,normal` | Which sizes get mirrored |
| `IMAGE_RATE_PER_SECOND` | `10` | Being gentle on Scryfall |
| `IMAGE_MAX_BYTES` | `3221225472` | Emergency brake against a full disk |

How it runs: determine every owned printing, check per size and face what is
missing, fetch the rest rate-limited – writing to `.tmp` first, then renaming,
so an abort leaves no half files behind. A table
`image_files (id, face, size, ts, bytes, fetched_at)` remembers the cache
buster from the catalog URL; if Scryfall changes an artwork, exactly that file
is fetched again. First run: ~27,500 files, about 45 minutes at 10/s –
unproblematic overnight, only new additions afterwards.

### So that it does not break silently

The lesson from the import incident in September:

- Metrics through the existing exporter: `mtg_images_mirrored`,
  `mtg_images_bytes`, `mtg_image_sync_last_timestamp_seconds`,
  `mtg_image_downloads_total{result}` – plus a panel and an alert in the
  Grafana repo.
- Entries in the activity log, as for sync and backup.
- A manual trigger via `POST /api/images/sync` (password protected).
- Cleaning up images no longer owned is **off** by default: an import replaces
  the collection entirely, so a bad import would otherwise wipe half the
  mirror.

### Order

Switch, nginx fallback and serving first – that way you can go live with an
empty volume and simply nothing happens. Then the sync job, finally symbols and
metrics.

Scryfall explicitly permits caching the images but asks for moderate request
rates (hence the rate limit) and for attribution, which the footer already
carries.

---

## 3. Flip double-faced cards — done

> Implemented in `shared.js` (`cardFaceHTML`, `initCardFlip`) and `style.css`.
> The editions overlay still shows the back as a second image; it is picked up
> with point 2, when `/api/sets/{code}/cards` moves to derived URLs anyway.

### Before

`cardGroupModalHTML()` in `shared.js` renders both images below each other in
the same column for a double-faced card. That is hard to read, costs a lot of
height and does not show the card the way you hold it. 80 entries are affected
at the moment – transform cards, modal DFCs, sagas with a back face.

### Goal

One card image that can be flipped: clicking the image or a small button
("↻ Umdrehen" / "↻ Flip") turns it to the back and back again with a short 3D
animation.

### Implementation

Purely frontend, no backend part – the `hasBack` flag and the derivable back
URL have existed since the payload rework.

```html
<div class="card-flip" data-flipped="false">
  <img class="face front" src="…/front/…">
  <img class="face back"  src="…/back/…">
</div>
```

```css
.card-flip { perspective: 1200px; }
.card-flip .face { backface-visibility: hidden; transition: transform .5s; }
.card-flip .back { transform: rotateY(180deg); position: absolute; inset: 0; }
.card-flip[data-flipped="true"] .front { transform: rotateY(180deg); }
.card-flip[data-flipped="true"] .back  { transform: rotateY(360deg); }
```

The details that make the difference:

- **Only where there is a back face.** Without `hasBack` it stays the simple
  `<img>` of today, with no button and no click behaviour.
- **Load the back only when needed** (`loading="lazy"` is not enough, because
  the image is visible in the DOM): set `src` on the first flip. Saves ~100 KB
  every time such a card is opened.
- **Keyboard and screen readers:** the button is a real `<button>` with
  `aria-pressed`, and the image gets a meaningful `alt` per face.
- **Respect motion preferences:** switch without animation under
  `prefers-reduced-motion: reduce`.
- **Bilingual** through the existing `t()`, like the rest of the interface.
- **In the editions overlay too** (`editions.js` shows back faces via
  `/api/sets/{code}/cards` as well), using the same component, so there is only
  one place for it.

Effort: modest, ~60 lines of JS and ~20 lines of CSS. A good interlude between
the two bigger items.

---

## 4. A wishlist, with a bridge to Cardmarket – done

> Implemented in `backend/wishlist.go` (table, four endpoints, the shared
> cheapest-printing aggregation, the Cardmarket export) plus `app/wishlist.html`
> / `app/js/wishlist.js` and the markers in `reserved.js`, `editions.js`,
> `deck-checker.js` and the card modal in `shared.js`. What the plan did not
> foresee is collected under *What changed while building it* at the end.

Mark cards you are missing wherever you notice they are missing, see them on a
page of their own, and get from there to Cardmarket with as little friction as
Cardmarket allows.

### Why this is worth more than a list of names

Any spreadsheet can collect names. What this portal has and Cardmarket does not
is **the data behind a buying decision**: a daily price point per card
(`price_history`), the full catalog of every printing, and what you already
own. A wishlist that sits on top of that can say *"18 % cheaper than 30 days
ago"* and *"this other printing costs 40 % less than the one you marked"*. That
is the feature; the marking is just the input.

### Data model

```sql
CREATE TABLE wishlist (
  name        TEXT PRIMARY KEY,   -- Scryfall card name, the identity
  set_code    TEXT,               -- optional: a printing you specifically want
  collector_number TEXT,          -- "
  target_eur  REAL,               -- optional: notify below this price
  min_condition TEXT,             -- optional: 'near_mint', 'excellent', ...
  note        TEXT,
  priority    INTEGER DEFAULT 0,
  added       TEXT
);
```

**Keyed by name, not by printing.** You want *Mishra's Workshop*, not
necessarily the Antiquities one. This also matches what the rest of the code
already does: `reserved.go` identifies Reserved List cards by name (every
printing is reserved), and `deck-checker.js` builds its owned index by name.
`set_code`/`collector_number` stay optional for the case where the printing
really does matter.

### Two things in the existing code that have to change

These are the reason for writing this down before coding:

**1. `price_history` only covers cards you own.** `snapshotValue()` in
`valuehistory.go` inserts `FROM collection c JOIN scryfall_cards s` – a card
that is not in the collection has no price points, so the price curve, the
"18 % cheaper" line and any target-price check would have nothing to work with
on exactly the cards this feature is about. The insert needs a second source
for the cheapest printing of every wished name. Cost: one row per wishlist
entry per day, i.e. nothing.

**2. `backupTables` in `backup.go` has to include `wishlist`.** The list is
user data that cannot be recreated from Scryfall – the same category as
`collection` and `value_snapshots`. Forget this and a restore silently drops
the whole wishlist, which is exactly the kind of failure that only shows up
when it hurts.

### Condition

The collection already records a condition per entry (`collection.condition`,
part of the uniqueness key, charted on the dashboard), and `shared.js` already
has the vocabulary and the labels in `CONDITION_LABEL` / `conditionLabel()`.
The wishlist should reuse both rather than invent a second scale.

**On the buying side it is an annotation, not a filter.** `min_condition` says
what you would accept when buying – it is carried to Cardmarket and shown on
the page. What it cannot do is sharpen the price: Scryfall delivers exactly one
`price_eur` and one `price_eur_foil` per printing (the Cardmarket trend price),
with no condition dimension anywhere in `scryfall_cards`. A target price of
"8 € but only Near Mint" is therefore not checkable against any data the portal
holds. The target price stays a single number, and the condition rides along as
the constraint you apply yourself at the shop.

**Cardmarket carries the condition in the wants list, not in the paste.** The
decklist format has no documented token for it; minimum condition is a field on
the wants-list row, set in Cardmarket's own UI after the import. Practical
consequence for the export: group by `min_condition` and emit one block per
tier, so each block becomes one list whose condition you set once – rather than
pasting everything into one list and correcting 150 rows by hand. Worth
confirming in the live UI before building the export, because it decides
whether the grouping is useful or pointless.

**The other direction is the more interesting one: upgrades.** Cards you own
*below* the condition you would like – `collection.condition` already knows
them, no new input required. A wishlist entry could therefore have two origins:
"missing" and "owned, but worse than wanted". Measured against the real
collection this is currently a thin seam: of 31,721 cards, 31,690 are
`near_mint` and 11 `mint`; below that there are 20 in total (7 good, 6 played,
5 light_played, 2 excellent). So the upgrade view is worth maybe a filter on
the wishlist page, not a mechanism of its own.

**One caveat on the data.** `csvimport.go` stores the condition from the CSV
verbatim – there is no normalisation. `CONDITION_LABEL` maps both
`light_played` and `lightly_played` onto the same label, which suggests both
spellings have been seen. Any comparison "is what I own worse than what I
want?" therefore needs a rank table (mint > near_mint > excellent > good >
light_played > played > poor/damaged), not a string comparison, and it has to
treat an unknown value as "no statement" instead of "worst".

### Endpoints

| Method | Path | Purpose |
| --- | --- | --- |
| `GET` | `/api/wishlist` | Entries, each enriched with the cheapest printing, its price, the 30-day change and whether the target is met |
| `POST` | `/api/wishlist` | Add or update one entry (password protected) |
| `DELETE` | `/api/wishlist/{name}` | Remove one entry (password protected) |
| `GET` | `/api/wishlist/export` | The list in Cardmarket's decklist format, grouped by minimum condition and chunked into blocks of 150 |

The cheapest-printing lookup is the aggregation `reserved.go` already does for
"investment needed to buy the rest" – worth factoring out rather than writing
twice.

Writes are password protected, like `/api/config`. Marking is therefore only
possible while logged in, which matches how every other action behaves.

### Where the marking happens

A small toggle on the card tile, filled when the card is on the list:

- **Reserved List** – on every missing card; the page already knows which.
- **Editions overlay** – on the greyed-out cards of a set.
- **Deck checker** – on the "Missing" rows, which is where the need usually
  comes up, and in bulk: "put everything missing on the wishlist".
- **Card grid** – for the "I want another copy / another printing" case.

### The page

`wishlist.html` plus a `wishlist` entry in `NAV_ITEMS` (`shared.js`), so the
admin can hide it through `hiddenNav` like any other page. Per entry: image,
cheapest printing with its set, price, the 30-day change, target price,
minimum condition, note, and a link to Cardmarket. On top: total, number of entries, how many are at or
below their target.

### Cardmarket: what works and what does not

Checked, because this decides the shape of the feature:

- **The API is out.** Cardmarket's help center states plainly that they are not
  accepting applications for API access, and the old documentation host answers
  with `410 Gone` (it moved to `apiv2.cardmarket.com`). There *is* wants-list
  management in the API – it is simply not reachable for a new project. Using
  someone else's credentials is forbidden by the same page.
- **The Shopping Wizard cannot be driven by URL.** It runs on a wants list
  inside a logged-in account; no URL parameters are documented, and the
  community tools around it need an open Cardmarket session in the browser to
  get past Cloudflare. Nothing to build on.
- **What does work: the decklist paste field.** A Cardmarket wants list can be
  filled by pasting one entry per line:

  ```
  4 Dark Ritual
  4x High Tide (V.1) (Fallen Empires)
  2 Dark Confidant (Modern Masters)
  Tarmogoyf
  ```

  Without parentheses Cardmarket takes any printing, with them you pin the
  version and expansion – which maps exactly onto the optional
  `set_code`/`collector_number` above. So the export is a "copy for Cardmarket"
  button, then one paste at *Buying → My Wants → Add Decklist*, and the
  Shopping Wizard starts from there.
- **Limits:** 150 entries per wants list, 100 lists per game – hence the
  chunking in the export.

Single cards keep the direct route they already have: every card carries its
`cardmarket_uri` and links straight to the product.

### So that it does not break silently

- Metrics through the existing exporter: `mtg_wishlist_items`,
  `mtg_wishlist_value_eur`, `mtg_wishlist_targets_met` – plus a panel in the
  Grafana repo, which is also the notification channel for a reached target
  price (no second mechanism needed, the alerting is already there).
- The target-price check rides along with the daily sync that writes the price
  snapshot; no new scheduler.

### Effort

Backend ~250 lines (table, CRUD, the shared cheapest-printing aggregation,
export), `wishlist.html` + `wishlist.js` ~250 lines, the toggles in four places
~40 lines, metrics ~30. Plus the two changes above, which are small but must
not be forgotten.

### What changed while building it

The plan survived mostly intact. Four things did not, and all four were found
by running the thing rather than by reading it:

**`min_condition` joined the table.** The plan had no condition field at all.
It is an annotation, not a filter – see the *Condition* section above – but it
drives the export grouping and the upgrade flag, so it belongs in the row.

**The wishlist snapshot has to pre-aggregate the minimum.** The obvious way to
write "the cheapest printing of each wished name" is a correlated subquery. On
110k catalog rows that is a full scan per row, it never finishes, and because
the database runs on a single connection (`SetMaxOpenConns(1)`) it takes the
whole backend down with it – every request hangs, including `/api/health`’s
neighbours. The version that shipped joins a grouped subquery instead.

**`scryfall_cards` had no index on `name`.** Half the app asks by name –
`/api/prints`, the Reserved List, now the wishlist – and every one of those was
a full scan. `idx_scryfall_cards_name` was overdue regardless of this feature.

**"Cheapest printing" was wrong, and had been all along.** The raw catalog
minimum for a Black Lotus is the 30th Anniversary proxy at ~2,300 EUR, for a
Force of Will a gold-bordered World Championship card at ~17 EUR. Neither is a
card you can play or sensibly buy, so neither answers "what would this cost".
`cheapestByName()` now excludes digital and `memorabilia` sets. The Reserved
List shares that aggregation and was wrong in the same way: its "cost to
complete" went from 38,957 EUR to **79,580 EUR**, which is the honest number.
No Reserved-List card dropped off the page (571 before and after) – every one
of them also exists in a normal set.

**Two smaller deviations.** The restore path now skips tables a backup does not
carry; without that, restoring any backup taken before today would fail outright
on the missing `wishlist` table – and an old backup is exactly the one you
restore in an emergency. And the export falls back to showing the text on the
page instead of opening a popup, because the Clipboard API needs a secure,
focused context and a popup is the kind of thing a browser blocks.
