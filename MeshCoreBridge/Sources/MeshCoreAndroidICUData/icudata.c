// Filtered ICU 76.1 locale data for the Android static-stdlib link.
// Pinned from GoodNotes/swift-icudata-slim@adf19e64536f686aaccd27747a7d7e44f505e139
// (Sources/icudata/76.1/default, locale en_001, default.json feature filter).
// Defining icudt76_dat here keeps lib_FoundationICU.a's packaged 32 MiB member
// from being extracted. Licensed under Unicode License V3 (UNICODE-LICENSE.txt).
#include <stdint.h>
#pragma clang diagnostic ignored "-Wc23-extensions"
#ifdef __cplusplus
extern "C" {
#endif
__attribute__((aligned(16))) const uint8_t icudt76_dat[] = {
#embed "icudt76l.dat"
};
#ifdef __cplusplus
}
#endif
