# The Nuxt server is the API's only client

The browser never calls the API. `aulaflix-web` renders on a Nitro server, so its server routes act as a
backend-for-frontend: they keep the session cookie on the web's own domain and call the API server to server,
translating its `/v1` contract (real status codes, `ProblemDetail`, money in integer cents) into the shapes the pages
expect, along with the pt-BR messages and the redirects. This keeps the API off the public internet except for the
payment webhooks, avoids CORS and cross-site cookies, and lets the API follow this repo's REST standards instead of the
web's page-level conventions.

## Considered Options

- **The browser calls the API directly**: rejected, since it needs CORS plus either a cross-site cookie or a token
  held in the browser, and it exposes every endpoint to the internet.

## Consequences

- Every request reaches the API from the BFF, so per-IP rate limiting only works if the BFF forwards the browser's IP
  and the API trusts that header from the BFF alone.
- Navigation (`redirect`, `signIn`) and user-facing wording belong to the web; the API never returns them.
