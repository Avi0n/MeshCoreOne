# Offline map estimate calibration

Captured 2026-10-28 using MapLibre 6.28.0. Receipts in `OfflineMapDownloadPolicyTests+RecordedSelections.swift` describe completed stored resource payload bytes, including shared resources. They do not measure network traffic or database allocation. All minimum camera zooms are 0.

Base style: https://tiles.openfreemap.org/styles/liberty

Style SHA-256: `408245464e76791801a64393c403433d09e8110eb9516040f8dc2b62584eee2a`

TileJSON: https://tiles.openfreemap.org/planet

Vector template: `https://tiles.openfreemap.org/planet/20261025_080012_pt/{z}/{x}/{y}.pbf`

Natural Earth template: `https://tiles.openfreemap.org/natural_earth/ne2sr/{z}/{x}/{y}.png`

Base supporting resources, shared by every captured base pack:

| Resource | Count | Stored bytes |
|---|---:|---:|
| Glyph PBFs for Noto Sans Regular, Bold, and Italic | 291 | 6,362,558 |
| Sprite PNGs, 1x and 2x | 2 | 162,620 |
| Sprite JSON, 1x and 2x | 2 | 5,115 |
| Liberty JSON | 1 | 3,982 |
| TileJSON | 1 | 1,477 |
| Total | 297 | 6,535,752 |

Topography uses bundled `topo-offline.json` with template `https://a.tile.opentopomap.org/{z}/{x}/{y}.png`. Its sole supporting resource is the compressed style: 222 bytes. Bundled style SHA-256: `1fe439df136313ad3b53c749a5cf05bedaa299f701267ce322eab78e5f3fff9b`.

These observations calibrate supporting-resource allowances only. Tile coefficients remain approximate. Corrected forecasts for the six receipts have signed errors from −42.41% to +83.46%; they do not establish a percentage accuracy guarantee. Refresh this calibration when style/source definitions change. Base resources can also change upstream at the same URLs.


Completed pack bytes and signed forecast errors are retained as calibration observations rather than arithmetic-only test assertions. Errors use `(estimate − actual) / actual`.

| Selection | Actual stored bytes | Corrected forecast bytes | Error |
|---|---:|---:|---:|
| Seattle base | 14,656,349 | 11,835,752 | −19.24% |
| Manhattan base | 10,531,082 | 7,095,752 | −32.62% |
| Nevada base | 8,930,372 | 12,165,752 | +36.23% |
| Pacific base | 7,928,377 | 7,645,752 | −3.56% |
| Alps base and topography | 12,591,008 | 7,250,974 | −42.41% |
| Seattle base and topography | 20,622,947 | 37,834,974 | +83.46% |
