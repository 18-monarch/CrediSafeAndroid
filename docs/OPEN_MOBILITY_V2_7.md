# Open Mobility v2.7

## Map layer

MapLibre Native renders `https://tiles.openfreemap.org/styles/liberty`. The app requires no map key or Google Maps billing. The renderer is intentionally separate from trip validity and scoring.

## Road intelligence

Every throttled road lookup sends the previous and current GPS points to the CrediSafe API. The API uses Valhalla `trace_attributes` to match that segment against an OpenStreetMap-derived graph and returns normalized road information.

A single point is not accepted as proof of road travel. Provider failure returns `providerAvailable=false`; it is never counted as off-road evidence. Possible rail/transit requires repeated available-provider samples plus repeated missing road matches and compatible motion evidence.

## Speed limits

Valhalla may expose a speed-limit value, but the value is not automatically treated as legally trusted. `VALHALLA_SPEED_LIMITS_TRUSTED=false` is the safe default. Overspeed logic requires a fresh limit, explicit trust, minimum confidence, and a concrete margin.

## Production path

The beta can use the FOSSGIS public Valhalla endpoint with a client identifier and fair usage. A mature production deployment should run Valhalla behind the CrediSafe API, monitor it, refresh OSM graphs, and define availability/error budgets. OpenFreeMap can later be replaced by a self-hosted style/PMTiles source without changing scoring.
