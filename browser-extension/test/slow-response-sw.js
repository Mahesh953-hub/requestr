// requestr Browser Bridge -- test-only Service Worker for test-page.html's slow/chunked
// response case (Task 13, case 11). Not part of the extension itself; only used to give
// the integration test suite a same-origin URL that streams its body over several delayed
// chunks, so the interceptor's captureResponseBody() reader loop is genuinely exercised
// against real network-timing behavior instead of an instantly-resolved data: URL.
//
// Intercepts fetches whose path contains "__requestr_slow__" and answers with a
// ReadableStream that enqueues three chunks with a real delay between each, then closes.
// Everything else falls through to the network exactly as normal.

self.addEventListener("install", () => self.skipWaiting());
self.addEventListener("activate", (event) => event.waitUntil(self.clients.claim()));

self.addEventListener("fetch", (event) => {
  const url = event.request.url;
  if (url.indexOf("__requestr_slow__") === -1) return;

  const marker = url.split("__requestr_slow__/")[1] || "unknown";
  const chunks = ["chunk1-", "chunk2-", marker];

  const stream = new ReadableStream({
    async start(controller) {
      for (const c of chunks) {
        await new Promise((r) => setTimeout(r, 150));
        controller.enqueue(new TextEncoder().encode(c));
      }
      controller.close();
    },
  });

  event.respondWith(
    new Response(stream, {
      status: 200,
      headers: { "content-type": "text/plain" },
    })
  );
});
