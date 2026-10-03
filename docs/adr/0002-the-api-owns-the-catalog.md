# The API owns the catalog

Courses, Modules, Lessons, prices and their marketing copy live in the API's database, and `aulaflix-web` fetches them
instead of compiling them from `shared/content/` as it does today. The API has to charge the right price and accept
progress only for Lessons that exist and are published, which it cannot do while the catalog lives in the client.

## Consequences

- The web must change: its pages, route validation and middleware import the catalog synchronously today.
- With no admin UI, the catalog is authored through Admin-only API endpoints rather than edited as code.
