#!/usr/bin/env bash
# Posts an Asaas webhook event to the local API, as Asaas would, for when the sandbox cannot reach this machine: the
# API's own webhook, with the token from secrets/aulaflix.asaas.webhook-token. The event only names the charge, or the
# Checkout; the webhook worker re-reads it from the sandbox with the API's key and acts on what the sandbox shows, so
# pay the charge in the sandbox first (Cobranças > the charge > Confirmar recebimento, or its Pix QR code).
#
# Usage: scripts/post-asaas-webhook.sh <event> <chargeId>
#        scripts/post-asaas-webhook.sh CHECKOUT_EXPIRED <checkoutId>
#   e.g. scripts/post-asaas-webhook.sh PAYMENT_RECEIVED pay_080225913252
# AULAFLIX_API_URL overrides the API's address, http://localhost:8080 by default.
set -euo pipefail

if [ "$#" -ne 2 ]; then
    sed -n '7,10p' "$0" | sed 's/^# \{0,1\}//' >&2
    exit 2
fi
event="$1"
id="$2"
api="${AULAFLIX_API_URL:-http://localhost:8080}"
token_file="$(dirname "$0")/../secrets/aulaflix.asaas.webhook-token"
if [ ! -r "$token_file" ]; then
    echo "No webhook token at $token_file: see the README's Running locally." >&2
    exit 1
fi
token="$(tr -d '\r\n' < "$token_file")"

# Asaas's event ids are unique; a repeated one is stored once, so each run gets its own
event_id="evt_dev_$(date +%s%N)"
if [ "$event" = "CHECKOUT_EXPIRED" ]; then
    body="$(printf '{"id": "%s", "event": "%s", "checkout": {"id": "%s"}}' "$event_id" "$event" "$id")"
else
    body="$(printf '{"id": "%s", "event": "%s", "payment": {"object": "payment", "id": "%s"}}' \
        "$event_id" "$event" "$id")"
fi

# The token goes in through a file descriptor, so that it never shows in the process list
status="$(curl --silent --show-error --output /dev/null --write-out '%{http_code}' \
    --header @<(printf 'asaas-access-token: %s\n' "$token") \
    --header 'Content-Type: application/json' \
    --data "$body" \
    "$api/v1/webhooks/asaas")"
echo "$event $event_id for $id: HTTP $status"
[ "$status" = "200" ]
