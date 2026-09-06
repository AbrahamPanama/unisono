#include <CoreAudio/CoreAudio.h>
#include <stdint.h>
typedef struct AudioRing AudioRing;
AudioRing *ring_create(void);
void ring_free(AudioRing *r);
void ring_push(AudioRing *r, const AudioBufferList *b, uint64_t host);
int ring_pop(AudioRing *r, float *dst, uint32_t *frames, uint64_t *host);
uint64_t ring_overflows(AudioRing *r);
