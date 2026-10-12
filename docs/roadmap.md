# Roadmap

Three pieces of work, planned in this order. The numbers come from measurements
against the real collection (as of 2026-10-12: 15,674 entries, 13,749 distinct
printings, 31,721 cards).

What is already done and why is in the git history – in short: `/api/dashboard`
aggregates server-side (2.4 MB → 1.8 KB), `/api/collection` and `/api/sets`
answer conditional requests with a 304, and image URLs are derived in the
browser from the Scryfall ID instead of being transferred (14.8 → 10.0 MB).

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
