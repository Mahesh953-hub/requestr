Yes. I compared the large interception taxonomy against the actual TODO.md, not against what Requestr is intended to become. That distinction matters because the TODO already marks a lot as implemented, including HTTP/2, WebSocket, SSE, DNS, QUIC/SNI, replay, HAR, API discovery, schema reconstruction, plugins, Local API, CLI, and workspace support.  

I also checked current Android and Chromium/Frida documentation where the implementation details matter. Android VpnService operates at the IP-packet/TUN layer and supports per-app allow/deny routing, while CDP provides browser-native request interception and initiator information. Frida supports injected instrumentation as well as Gadget-based/repackaged approaches. 

There is one important boundary: I can go extremely deep on architecture, implementation, files, APIs, state machines, diagnostics, and legitimate testing instrumentation. I am not going to provide instructions whose purpose is defeating another application's security controls or concealing instrumentation from it. For Requestr, that actually leads to a better design anyway: detect the visibility boundary, use the appropriate observation plane, and report exactly what Requestr can and cannot see.

What is genuinely missing

I would divide the gap into five groups:

A. Completely absent interception planes
B. Existing capabilities that lack a proper interception implementation
C. Correlation infrastructure that Requestr needs
D. Modern browser interception
E. Advanced transport / runtime observation

The biggest missing pieces are:

1. CDP Browser Bridge
2. Browser Extension Bridge
3. Browser Runtime / Initiator correlation
4. Service Worker observation
5. Browser storage correlation
6. Android runtime instrumentation engine
7. Runtime-to-network correlation
8. Native socket observation
9. TLS-layer observation
10. Gateway / external-network interception mode
11. Packet capture / pcap subsystem
12. TCP/UDP connection model
13. QUIC connection model beyond SNI
14. HTTP/3 semantic layer
15. DoH / DoT detection
16. gRPC/protobuf protocol layer
17. Application-integrated instrumentation SDK
18. HTTP-client middleware adapters
19. Request provenance graph
20. Connection lineage graph
21. Multi-plane correlation engine
22. Differential interception
23. Automatic HTTP-stack detection
24. Protocol fingerprinting
25. Interception capability/visibility diagnostics

Now the important part.


---

1. CDP Browser Bridge

This is probably the #1 missing feature.

Our TODO has browser-oriented interception concepts elsewhere, but there is no actual Chromium/CDP interception subsystem in the listed implementation. The existing TODO specifically describes Android VPN, MITM, Frida, parsers, etc., but not a CDP client or browser bridge. 

What it is

Chrome DevTools Protocol lets Requestr communicate directly with Chromium.

Chrome
   |
   | WebSocket / CDP
   v
Requestr BrowserBridge
   |
   v
Interceptr

Unlike MITM, this operates inside the browser's own debugging interface.

CDP's Network domain exposes requests, responses, headers, bodies, timing, cookies and initiators. Its Fetch domain can pause matching requests and continue, fail, or fulfill them. 

What it enables

Capture request
Capture response
Get request body
Get response body
Get initiator
Get JS stack
Observe redirects
Observe WebSockets
Observe EventSource
Observe failed requests
Block request
Modify request
Fulfill response
Inject controlled response

Requestr files

I'd create:

browser/
├── BrowserBridge.kt
├── CdpClient.kt
├── CdpConnection.kt
├── CdpMessage.kt
├── CdpEvent.kt
├── CdpRequest.kt
├── CdpResponse.kt
├── CdpTarget.kt
├── CdpTargetManager.kt
├── CdpNetworkAdapter.kt
├── CdpFetchAdapter.kt
├── CdpRuntimeAdapter.kt
├── BrowserCaptureEngine.kt
├── BrowserInterceptionController.kt
└── BrowserCapabilityDetector.kt

Data:

data/browser/
├── BrowserTargetEntity.kt
├── BrowserRequestEntity.kt
├── BrowserEventEntity.kt
└── BrowserSessionEntity.kt

Repository:

data/repository/
└── BrowserRepository.kt

Settings page

Create:

Settings
  > Browser Bridge

Options:

Enable Browser Bridge
Auto-connect to Chromium
Capture Network
Capture Request Bodies
Capture Response Bodies
Capture WebSocket
Capture SSE
Capture Initiators
Capture JS Stack Traces
Observe Service Workers
Observe Browser Storage

Browser page

Add:

Browser

with:

Connected browser
Tabs
Frames
Network state
Bridge state

Actual implementation requirement

Do not make the UI say:

Browser interception: ON

until:

CDP socket connected
Target discovered
Network.enable succeeded

The UI should instead say:

CONNECTED
NETWORK: ACTIVE
RUNTIME: ACTIVE
FETCH: ACTIVE

That fits Our existing philosophy of removing fake capabilities. Our TODO explicitly calls out cases where UI claims functionality that isn't actually wired. 


---

2. Browser Extension Bridge

CDP is excellent, but an extension gives Requestr another integration surface.

Architecture:

Chrome Extension
      |
      v
Native / Local Bridge
      |
      v
Requestr Local API

We already have a Local API on 127.0.0.1:9876. 

That means this is particularly compatible with Our existing architecture.

Extension functions

Capture current page
Capture selected request
Send request to API Testr
Save request
Create Flow
Create Tool
Send to AI
Export HAR
Open Interceptr

Files

browser-extension/
├── manifest.json
├── background/
│   └── service-worker.js
├── devtools/
│   ├── devtools.html
│   └── devtools.js
├── popup/
│   ├── popup.html
│   └── popup.js
└── bridge/
    └── requestr-client.js

The Android side:

browser/
└── BrowserExtensionBridge.kt

Settings

Settings
 > Browser
 > Extension Bridge

Enable
Port
Authentication token
Auto-import requests
Auto-open captured requests


---

3. Browser Runtime / Initiator Correlation

CDP's Network domain exposes request initiators and, for script initiated requests, JavaScript stack information when the relevant debugger functionality is enabled. 

This should become a core Requestr feature.

Instead of:

GET /api/messages

show:

GET /api/messages

Initiator:
script

JavaScript:
app.js

Function:
loadMessages()

Line:
1821

Files

browser/
├── InitiatorResolver.kt
├── StackTraceResolver.kt
└── BrowserRequestCorrelator.kt

Data model

RequestProvenance

containing:

requestId
targetId
frameId
initiatorType
initiatorUrl
scriptUrl
functionName
lineNumber
columnNumber
stackTrace


---

4. Service Worker Observation

Modern web apps can serve data from:

Service Worker
   |
   +-- Cache
   +-- Network

So:

User clicks button
      |
      v
fetch()
      |
      v
Service Worker
      |
      +---- Cache HIT
      |
      +---- Network

Requestr needs to know which occurred.

CDP's Network domain includes service-worker-related controls and metadata. 

Feature

Request details:

Source

NETWORK
SERVICE_WORKER
CACHE

Files

browser/
├── ServiceWorkerObserver.kt
├── CacheObserver.kt
└── BrowserStorageObserver.kt


---

5. Browser Storage Correlation

Missing.

We want:

Cookies
LocalStorage
SessionStorage
IndexedDB
CacheStorage

but not as an isolated browser inspector.

Correlate them with requests.

Example:

LOGIN
 ↓
Set-Cookie
 ↓
CookieStore mutation
 ↓
GET /profile
 ↓
Cookie attached

Files

browser/
├── CookieObserver.kt
├── LocalStorageObserver.kt
├── SessionStorageObserver.kt
├── IndexedDbObserver.kt
└── CacheStorageObserver.kt

UI

Inside request details:

Request Context

Cookies used:
session_id

Storage touched:
localStorage.auth

Service Worker:
none


---

6. Android Runtime Instrumentation Engine

Our TODO currently calls out Frida as a capability whose UI currently says "Detected (CLI only)" because the application isn't actually injecting or streaming it. 

That means this isn't merely a future feature.

It is an existing gap.

Required architecture

RuntimeInstrumentation
       |
       +-- Frida
       +-- Xposed-compatible adapter
       +-- Debugger adapter
       +-- Native adapter
       +-- App SDK adapter

Create:

runtime/
├── RuntimeInstrumentationEngine.kt
├── RuntimeSession.kt
├── RuntimeEvent.kt
├── RuntimeEventBus.kt
├── RuntimeAdapter.kt
├── RuntimeCapability.kt
├── RuntimeTarget.kt
└── RuntimeSessionManager.kt

Frida:

runtime/frida/
├── FridaClient.kt
├── FridaSession.kt
├── FridaScript.kt
├── FridaScriptRepository.kt
├── FridaEventParser.kt
└── FridaProcessManager.kt

Settings

Settings
 > Runtime Instrumentation

Enable
Connection
Device
Target application
Session timeout
Event logging
Script directory
Auto-attach

Crucial rule

The UI must expose capability state:

Unavailable
Detected
Connected
Attached
Streaming
Failed
Stopped

Not:

Frida: Available

when nothing is actually connected.


---

7. Runtime-to-Network Correlation

This is one of the largest architectural gaps.

Suppose Frida reports:

OkHttp request

and VPN reports:

TCP connection

and MITM reports:

GET /api/user

Requestr must determine:

These are the same request.

Create

correlation/
├── CorrelationEngine.kt
├── CorrelationEvent.kt
├── CorrelationKey.kt
├── CorrelationRule.kt
├── CorrelationStore.kt
├── RequestMatcher.kt
├── RuntimeNetworkMatcher.kt
└── CorrelationRepository.kt

Matching signals

Use combinations of:

timestamp
process
package
destination
port
URL
connection
thread
request ID
stream ID
socket
headers

Never depend on one signal.

Result

REQ-421
 |
 +-- RuntimeEvent #991
 +-- Socket #31
 +-- TLS #44
 +-- HTTP/2 Stream #7
 +-- DNS #18


---

8. Native Socket Observation

Missing.

Our VPN sees packets.

Our MITM sees HTTP.

Our runtime layer can see application calls.

But We need a native socket observation model.

Conceptually:

connect()
send()
recv()
read()
write()
close()

Data model

SocketEvent

Fields:

pid
uid
process
fd
family
protocol
sourceAddress
sourcePort
destinationAddress
destinationPort
timestamp
operation
byteCount

Files

runtime/native/
├── NativeSocketAdapter.kt
├── SocketEvent.kt
├── SocketConnection.kt
└── SocketCorrelation.kt

For the Android side, the implementation mechanism depends heavily on device privileges and target architecture. The application should expose the capability as conditional, not promise universal access.


---

9. TLS Observation Layer

Our MITM has TLS decryption when it can establish the interception path, but that is not the same as having a dedicated TLS observation model.

Create:

interception/tls/
├── TlsSession.kt
├── TlsHandshake.kt
├── TlsCertificate.kt
├── TlsMetadata.kt
├── TlsDetector.kt
└── TlsRepository.kt

Track:

version
cipher
ALPN
server name when observable
certificate
certificate chain
handshake timestamps
connection ID
protocol

The point is:

HTTP decrypted

and:

TLS observed but HTTP opaque

must be different states.


---

10. External Gateway Mode

Missing.

Current Requestr is device-local.

Add:

Gateway Mode

Architecture:

Phone / Device
      |
      v
Wi-Fi / Network
      |
      v
Requestr Gateway
      |
      v
Internet

This can be another Requestr instance, desktop bridge, or controlled gateway.

Settings

Gateway
  Host
  Port
  Authentication
  TLS
  Capture policy

UI

Gateway
CONNECTED

Devices:
3

Traffic:
12,482 requests

This is useful for testing multiple devices and comparing behavior.


---

11. Packet Capture / PCAP Subsystem

Our current VPN packet pump already gives We an excellent starting point.

But packet capture should become a proper subsystem.

Files

interception/packet/
├── PacketCaptureEngine.kt
├── Packet.kt
├── PacketParser.kt
├── IpParser.kt
├── TcpParser.kt
├── UdpParser.kt
├── Ipv6Parser.kt
├── PcapWriter.kt
└── PacketRepository.kt

Features

IPv4
IPv6
TCP
UDP
ICMP metadata
DNS
TLS
QUIC

UI

Connections
Packets
Protocols

Don't dump raw packets into the Interceptr request table.

Have:

Interceptr
Packet Inspector
Connection Inspector


---

12. TCP Connection Model

Our existing HTTP parsers need a stronger underlying connection model.

Create:

TcpConnectionEntity

with:

connectionId
source
destination
sourcePort
destinationPort
state
createdAt
closedAt
bytesSent
bytesReceived
retransmissions

Then:

TCP connection
 ↓
TLS session
 ↓
HTTP connection
 ↓
HTTP stream
 ↓
Request

This is the foundation for correlation.


---

13. UDP Connection Model

Same for:

UDP
QUIC
DNS

Create:

UdpFlowEntity

and:

QuicConnectionEntity

The important distinction is that QUIC isn't TCP with a different parser.

It needs its own connection/stream model.


---

14. HTTP/3 Semantic Layer

Our TODO currently has QUIC SNI extraction, which is useful but only an early layer. 

We need:

QUIC
 ↓
HTTP/3
 ↓
HTTP request/response model

when the required visibility is available.

Create:

interception/http3/
├── Http3Connection.kt
├── Http3Stream.kt
├── Http3Frame.kt
├── Http3Parser.kt
├── QpackDecoder.kt
└── Http3Repository.kt

And UI:

Protocol: HTTP/3
Connection: QUIC #18
Stream: 7


---

15. DoH / DoT Detection

Our DNS parser handles ordinary UDP/53 traffic. 

Add:

DNS
DoT
DoH

classification.

Files:

interception/dns/
├── DnsDetector.kt
├── DohDetector.kt
├── DotDetector.kt
└── DnsTransport.kt

The UI should explicitly say:

DNS visibility:

UDP/53: decoded
DoT: encrypted
DoH: encrypted / browser-observed

rather than falsely claiming DNS capture is universal.


---

16. gRPC / Protobuf

This is a significant missing modern protocol.

We currently have HTTP/2.

But many APIs aren't JSON.

They are:

HTTP/2
+
gRPC
+
Protobuf

So:

HTTP/2
 ↓
gRPC
 ↓
protobuf

Create:

interception/grpc/
├── GrpcDetector.kt
├── GrpcFrame.kt
├── GrpcParser.kt
├── ProtobufDecoder.kt
├── ProtoSchema.kt
└── GrpcRepository.kt

UI

Protocol:
gRPC

Service:
UserService

Method:
GetUser

Request:
protobuf

Response:
protobuf

Unknown protobuf fields should still be displayed structurally when possible.


---

17. Application Instrumentation SDK

This is one of my favorite additions for Requestr.

Don't always fight the network.

For applications We control, provide:

Requestr Observer SDK

Concept:

Application
   |
Requestr SDK
   |
Local bridge
   |
Requestr

The SDK emits:

request started
request headers
request body
response headers
response body
timing
exception
network stack

Android SDK module

requestr-sdk/
├── RequestrObserver.kt
├── RequestrEvent.kt
├── RequestrInterceptor.kt
├── RequestrTransport.kt
└── RequestrConfig.kt

Configuration

RequestrObserver.initialize(
    RequestrConfig(
        endpoint = "...",
        captureBodies = true,
        captureHeaders = true
    )
)

This gives developers an officially supported observation path.


---

18. HTTP Client Adapter System

Instead of hardcoding every library:

HTTP Adapter

interface:

interface HttpStackAdapter {
    fun detect(...)
    fun attach(...)
    fun observe(...)
}

Adapters:

OkHttpAdapter
CronetAdapter
WebViewAdapter
URLConnectionAdapter
BrowserFetchAdapter
CustomAdapter

Files:

runtime/http/
├── HttpStackAdapter.kt
├── HttpStackDetector.kt
├── OkHttpAdapter.kt
├── CronetAdapter.kt
├── WebViewAdapter.kt
└── BrowserFetchAdapter.kt


---

19. Request Provenance Graph

This should become a flagship Requestr feature.

Instead of:

REQ-421

show:

Button click
   ↓
JavaScript function
   ↓
fetch()
   ↓
Service Worker
   ↓
HTTP/2 stream
   ↓
TLS
   ↓
Socket
   ↓
Server

For Android:

UI action
 ↓
Kotlin function
 ↓
OkHttp
 ↓
TLS
 ↓
Socket
 ↓
VPN
 ↓
Server

Create:

correlation/graph/
├── ProvenanceGraph.kt
├── ProvenanceNode.kt
├── ProvenanceEdge.kt
├── GraphBuilder.kt
└── GraphRepository.kt

UI:

Request
 > Provenance


---

20. Connection Lineage

Different from provenance.

Provenance answers:

Why did this happen?

Connection lineage answers:

How did this request travel?

Example:

DNS #12
 ↓
IP 1.2.3.4
 ↓
TCP #31
 ↓
TLS #44
 ↓
HTTP/2 #17
 ↓
Stream #7
 ↓
REQ-421

Add:

ConnectionLineage.kt
ConnectionLineageBuilder.kt


---

21. Multi-plane Correlation

This is the architectural monster.

Suppose:

VPN:
REQ A

MITM:
REQ A

Runtime:
REQ A

Browser:
REQ A

Merge them.

But:

Runtime:
REQ B

VPN:
nothing

Then show:

Runtime-only observation

And:

VPN:
REQ C

Runtime:
nothing

Show:

Network-only observation

This is extraordinarily useful because it tells We where visibility was lost.


---

22. Differential Interception

Build a feature around the previous idea.

Observation Plane Comparison

Choose:

VPN
MITM
Runtime
Browser
Packet

Then:

Compare

Result:

REQ-421

VPN       ✓
MITM      ✓
Runtime   ✓
Browser   ✓

Correlation:
100%

Another:

REQ-422

VPN       ✓
MITM      ✗
Runtime   ✓
Browser   -

Visibility:
Runtime only + encrypted network transport

This should be a diagnostic page.


---

23. Automatic HTTP Stack Detection

Create:

HttpStackDetector

It should classify observed traffic as:

OkHttp
Cronet
URLConnection
WebView
Browser Fetch
Native
Unknown

Potential evidence:

runtime events
process metadata
library presence
connection behavior
protocol metadata
browser target

Never claim certainty unless evidence supports it.

Use:

Detected
Likely
Possible
Unknown


---

24. Protocol Fingerprinting

Create:

ProtocolDetector

Input:

bytes
connection metadata
port
TLS metadata
HTTP metadata

Output:

HTTP/1.1
HTTP/2
HTTP/3
WebSocket
SSE
gRPC
DNS
TLS
QUIC
Unknown binary

The detector should have confidence:

HTTP/2: 0.99
gRPC: 0.87

That is much better than:

protocol = UNKNOWN

everywhere.


---

25. Visibility / Capability Diagnostics

This is perhaps the most important UI feature.

Every capture session should have:

Observation Report

Example:

TARGET
MyApp

NETWORK
✓ VPN
✓ TCP
✓ DNS

TLS
✓ handshake metadata
✗ plaintext

HTTP
✗ decrypted

RUNTIME
✓ Frida connected

BROWSER
N/A

RESULT
Traffic is visible at the runtime and packet layers,
but HTTP plaintext is unavailable to this capture path.

That makes Requestr honest.

No fake green indicators.


---

26. The Settings architecture

We'd create:

Settings
├── Capture
├── VPN
├── MITM
├── Certificates
├── Runtime
├── Browser
├── Gateway
├── DNS
├── Protocols
├── Correlation
├── Storage
├── Rules
├── Flow
└── Diagnostics

Capture

Request logging
Response logging
Body capture
Maximum body size
Header capture
Cookie capture
Binary capture
PCAP capture

VPN

Enabled apps
Excluded apps
IPv4
IPv6
DNS
Routes
Bypass
Auto-start

Android's VPN builder explicitly supports allowed/disallowed applications and routes, while Android requires user preparation/consent for VPN use and only one VPN can normally be active at once. 

MITM

Enable MITM
CA
Certificate generation
Host certificate cache
TLS diagnostics
HTTP/1.1
HTTP/2

Runtime

Frida
Target
Auto attach
Runtime events
Stack traces
HTTP adapters

Browser

CDP
Port
Auto-connect
Network
Fetch
Runtime
Service Worker
Storage
WebSocket
SSE

Correlation

Enable correlation
Timestamp window
Strict matching
Heuristic matching
Store unmatched events


---

27. Phone-side requirements

This needs to be explicit in Requestr.

Do not make the user guess.

Create:

Settings
 > Device Capabilities

Show:

Android version
Architecture
Root
Shizuku
VPN permission
Developer options
USB debugging
ADB connectivity
CA installed
System CA access
IPv6
Network state
Battery restrictions
Background execution

For VPN, Android requires user preparation and only one VPN connection can normally be active. The OS also manages the VPN notification/lifecycle. 

For Frida, current Frida documentation notes that rooted devices are the straightforward route, while Gadget/repackaging and debugger-based approaches are alternative configurations. 

Requestr should translate this into a capability matrix:

No Root    Shizuku    Root
VPN                    ✓          ✓         ✓
MITM user CA           ✓          ✓         ✓
System CA              -          conditional  ✓
Runtime instrumentation conditional conditional ✓
Packet capture          ✓          ✓         ✓
Native observation      -          conditional ✓

Exact capabilities should be determined dynamically rather than hard-coded.


---

28. The implementation order

This is where We'd be ruthless.

Do NOT implement all of this randomly.

Our current TODO already has a lot of lower-level infrastructure. 

We'd build the missing architecture in this order:

PHASE 1
Capability model
      ↓
Observation event model
      ↓
Connection model
      ↓
Correlation engine

Then:

PHASE 2
TCP/UDP
      ↓
TLS
      ↓
HTTP/1.1
      ↓
HTTP/2
      ↓
HTTP/3

Then:

PHASE 3
Runtime
      ↓
Frida
      ↓
HTTP adapters
      ↓
Runtime correlation

Then:

PHASE 4
Browser
      ↓
CDP
      ↓
Network
      ↓
Fetch
      ↓
Runtime
      ↓
Service Worker

Then:

PHASE 5
Provenance graph
      ↓
Connection lineage
      ↓
Differential interception
      ↓
Visibility diagnostics

Then:

PHASE 6
gRPC
protobuf
DoH/DoT
gateway
PCAP


---

29. The files We'd ultimately expect

The current project should evolve toward something roughly like:

app/src/main/java/.../requestr/

interception/
├── CaptureEngine.kt
├── CaptureController.kt
├── CaptureMethodWatcher.kt
├── PreflightController.kt
│
├── vpn/
│   ├── RequestrVpnService.kt
│   ├── PacketPump.kt
│   ├── TcpReassembler.kt
│   └── UdpFlow.kt
│
├── proxy/
│   ├── MitmProxyEngine.kt
│   ├── CertificateAuthority.kt
│   └── ProxySession.kt
│
├── packet/
│   ├── PacketCaptureEngine.kt
│   ├── IpParser.kt
│   ├── TcpParser.kt
│   ├── UdpParser.kt
│   ├── PcapWriter.kt
│   └── PacketRepository.kt
│
├── tls/
│   ├── TlsDetector.kt
│   ├── TlsSession.kt
│   ├── TlsHandshake.kt
│   └── TlsRepository.kt
│
├── http/
│   ├── HttpParser.kt
│   ├── Http1Parser.kt
│   ├── Http2Parser.kt
│   └── HttpRepository.kt
│
├── http3/
│   ├── Http3Parser.kt
│   ├── Http3Connection.kt
│   ├── Http3Stream.kt
│   └── QpackDecoder.kt
│
├── websocket/
│   └── WebSocketParser.kt
│
├── sse/
│   └── SseParser.kt
│
├── grpc/
│   ├── GrpcDetector.kt
│   ├── GrpcParser.kt
│   └── ProtobufDecoder.kt
│
├── dns/
│   ├── DnsParser.kt
│   ├── DohDetector.kt
│   └── DotDetector.kt
│
└── quic/
    ├── QuicParser.kt
    ├── QuicConnection.kt
    └── QuicStream.kt

Then:

runtime/

├── RuntimeInstrumentationEngine.kt
├── RuntimeSession.kt
├── RuntimeEvent.kt
├── RuntimeEventBus.kt
├── RuntimeAdapter.kt
│
├── frida/
│   ├── FridaClient.kt
│   ├── FridaSession.kt
│   ├── FridaScript.kt
│   └── FridaEventParser.kt
│
├── native/
│   ├── NativeSocketAdapter.kt
│   └── SocketEvent.kt
│
└── http/
    ├── HttpStackAdapter.kt
    ├── HttpStackDetector.kt
    ├── OkHttpAdapter.kt
    ├── CronetAdapter.kt
    └── WebViewAdapter.kt

Then:

browser/

├── BrowserBridge.kt
├── CdpClient.kt
├── CdpConnection.kt
├── CdpTargetManager.kt
├── BrowserCaptureEngine.kt
├── BrowserRequestCorrelator.kt
├── InitiatorResolver.kt
├── ServiceWorkerObserver.kt
└── BrowserStorageObserver.kt

Then:

correlation/

├── CorrelationEngine.kt
├── CorrelationEvent.kt
├── CorrelationKey.kt
├── RequestMatcher.kt
├── RuntimeNetworkMatcher.kt
│
└── graph/
    ├── ProvenanceGraph.kt
    ├── ProvenanceNode.kt
    ├── ProvenanceEdge.kt
    ├── ConnectionLineage.kt
    └── GraphBuilder.kt

Then:

diagnostics/

├── CapabilityDetector.kt
├── VisibilityAnalyzer.kt
├── ObservationReport.kt
├── InterceptionHealth.kt
└── DifferentialAnalyzer.kt


---

30. What the final Interceptr should look like

Not:

Request
GET /api/user

Instead:

REQ-000421

SOURCE
Android App

OBSERVATION
VPN + MITM + Runtime

STACK
OkHttp

PROTOCOL
HTTP/2

CONNECTION
H2 #17

STREAM
7

TLS
TLS 1.3

DNS
api.example.com -> 1.2.3.4

RUNTIME ORIGIN
UserRepository.getUser()

THREAD
OkHttp Dispatcher

REQUEST
GET /api/user

RESPONSE
200

PROVENANCE
UI
 ↓
Repository
 ↓
OkHttp
 ↓
TLS
 ↓
HTTP/2
 ↓
VPN
 ↓
Internet

And for a browser:

REQ-000991

TARGET
Chrome / Tab 4

FRAME
main

INITIATOR
JavaScript

SCRIPT
app.js:1821

FUNCTION
loadMessages()

RUNTIME
fetch()

SERVICE WORKER
sw.js

CACHE
MISS

PROTOCOL
HTTP/2

REQUEST
GET /api/messages

RESPONSE
200

DOM EFFECT
message-list updated

That is the distinction between having interception features and having an interception platform.

Our TODO is already fairly deep on protocol parsing and the surrounding developer tooling. The largest architectural gap is now the observation/correlation layer. The existing project has parsers, capture engines, Flow, replay, API discovery and schema reconstruction, but the observations still need to become one coherent model.  

If I were driving the next Requestr milestone, I would not immediately add another random interceptor. I'd build:

ObservationEvent
       ↓
Connection Model
       ↓
CorrelationEngine
       ↓
ProvenanceGraph
       ↓
VisibilityAnalyzer
       ↓
Interceptr

Then plug VPN, MITM, Frida, CDP, packet capture and future adapters into that backbone.

That's the part that turns Requestr from a collection of interception mechanisms into something genuinely different.