#!/bin/sh
# Puts the storage in the state the API needs: the private bucket for Lesson videos, and the API's two users, each
# with its policy. It runs on every `docker compose up`, as storage-init, and in the tests' AIStor container, so each
# step sets that state whatever was there before, and a second run changes nothing.
#
# It reads every credential from /run/secrets/, and STORAGE_URL names the server.
set -eu

secret() {
    cat "/run/secrets/$1"
}

# The alias lives in the environment, so the root credentials never reach mc's configuration on disk
MC_HOST_storage="$(printf 'http://%s:%s@%s' "$(secret storage.root-user)" "$(secret storage.root-password)" \
    "${STORAGE_URL#http://}")"
export MC_HOST_storage

mc mb --ignore-existing storage/videos
mc anonymous set none storage/videos

mc admin policy create storage aulaflix-read-only /storage/policies/read-only.json
mc admin policy create storage aulaflix-read-write /storage/policies/read-write.json

mc admin user add storage "$(secret aulaflix.storage.read-only.access-key-id)" \
    "$(secret aulaflix.storage.read-only.secret-access-key)"
mc admin policy attach storage aulaflix-read-only --user "$(secret aulaflix.storage.read-only.access-key-id)"

mc admin user add storage "$(secret aulaflix.storage.read-write.access-key-id)" \
    "$(secret aulaflix.storage.read-write.secret-access-key)"
mc admin policy attach storage aulaflix-read-write --user "$(secret aulaflix.storage.read-write.access-key-id)"
