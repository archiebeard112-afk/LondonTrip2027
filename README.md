# Flight prices

`prices.json` is updated once a day by a scheduled Claude task. The London Trip app reads it from
this branch, shows the newest price and sends a notification when a new day's price appears.

Each entry in `history`, oldest first:

```json
{ "date": "2026-10-21", "price": 1412, "airline": "United", "stops": 0, "url": "https://www.google.com/travel/flights/..." }
```

- `price`: cheapest round-trip total for both adults, in whole US dollars.
- `url`: the page to open when the price is tapped (Google Flights opens inside the app).

This branch is separate from `main`, so updating prices never builds a new APK.
