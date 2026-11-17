// Cloudflare Worker: serves your R2 bucket (model + assets.json + bgm, best hook, transitionsfx, broll, frames)
// on a free *.workers.dev address, instead of the slow, rate-limited pub-….r2.dev address.
//
// Setup (Cloudflare dashboard):
//   1. Workers & Pages → Create → Worker → name it "clipper-files" → Deploy
//   2. Edit code → replace everything with this file → Deploy
//   3. Worker → Settings → Bindings → Add → R2 bucket
//        Variable name: BUCKET     R2 bucket: parakeet-models     → Deploy
//   4. Test: https://clipper-files.<your-name>.workers.dev/assets.json
//   5. Put https://clipper-files.<your-name>.workers.dev in the app (Settings → Model & assets → R2 bucket address)
//
// Supports HEAD (file sizes) and Range requests (the app resumes broken downloads).

export default {
  async fetch(request, env) {
    const url = new URL(request.url);
    const key = decodeURIComponent(url.pathname.slice(1));
    if (!key) return new Response("Auto Clipper files", { headers: { "content-type": "text/plain" } });
    if (request.method !== "GET" && request.method !== "HEAD") return new Response("Method not allowed", { status: 405 });

    const headers = new Headers();
    headers.set("accept-ranges", "bytes");
    headers.set("access-control-allow-origin", "*");
    // the file list changes when you upload new files: never cache it; everything else can be cached a day
    headers.set("cache-control", key === "assets.json" ? "no-cache" : "public, max-age=86400");

    if (request.method === "HEAD") {
      const h = await env.BUCKET.head(key);
      if (!h) return new Response(null, { status: 404 });
      h.writeHttpMetadata(headers);
      headers.set("etag", h.httpEtag);
      headers.set("content-length", String(h.size));
      return new Response(null, { headers });
    }

    const obj = await env.BUCKET.get(key, { range: request.headers, onlyIf: request.headers });
    if (!obj) return new Response("Not found", { status: 404 });
    obj.writeHttpMetadata(headers);
    headers.set("etag", obj.httpEtag);
    if (!("body" in obj)) return new Response(null, { status: 304, headers }); // not modified

    if (request.headers.has("range") && obj.range) {
      const r = obj.range;
      let start, len;
      if ("suffix" in r && r.suffix !== undefined) { len = Math.min(r.suffix, obj.size); start = obj.size - len; }
      else { start = r.offset ?? 0; len = r.length ?? (obj.size - start); }
      if (start >= obj.size) return new Response(null, { status: 416, headers: { "content-range": `bytes */${obj.size}` } });
      headers.set("content-range", `bytes ${start}-${start + len - 1}/${obj.size}`);
      headers.set("content-length", String(len));
      return new Response(obj.body, { status: 206, headers });
    }
    headers.set("content-length", String(obj.size));
    return new Response(obj.body, { headers });
  },
};
