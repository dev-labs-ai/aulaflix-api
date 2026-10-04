# Lesson videos are progressive MP4 files, encoded before upload

Each Lesson's video is a single H.264/AAC MP4 with its index at the start (`faststart`), encoded by the Admin with
ffmpeg before uploading it. The page plays it in a plain `<video>` element from an expiring presigned URL. The API
never transcodes. When a video is linked to a Lesson, the API reads the file's header to take the duration and rejects
anything else: not MP4, no `faststart`, video not H.264, audio not AAC. Seeking works through range requests, so the
page needs no CORS, no playlist and no player library, and the VPS's four cores never run an encoder next to the API
and PostgreSQL ([research](https://github.com/dev-labs-ai/aulaflix-api/issues/4),
[decision](https://github.com/dev-labs-ai/aulaflix-api/issues/10)).

## Considered Options

- **HLS with adaptive bitrate**, the usual choice for course platforms: rejected for the MVP. A signed playlist does
  not authorize its segments, so the API would have to rewrite every playlist with presigned segment URLs, and the web
  would need hls.js plus CORS on the storage. It remains the upgrade path, on the same bucket and signer.
- **Transcoding on the server** from the Admin's raw upload: rejected, since ffmpeg would compete with the API and
  PostgreSQL for the VPS's four cores, with no gain for the Student.

## Consequences

- There is no adaptive bitrate: a viewer whose connection is slower than the encoded bitrate buffers.
- The playback URL is a bearer token valid for 4 hours. Anyone holding it can watch or download the file until it
  expires, and a Refund does not cut off a URL already issued.
- Moving to HLS later means re-encoding every Lesson.
