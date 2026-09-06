#include "AudioRing.h"
#include <stdatomic.h>
#include <stdlib.h>
#include <string.h>
#define SLOTS 128
#define FRAMES 4096
typedef struct { uint32_t frames; uint64_t host; float data[FRAMES*2]; } Slot;
struct AudioRing { _Atomic uint64_t write, read, overflow; Slot slots[SLOTS]; };
AudioRing *ring_create(void) { return calloc(1, sizeof(AudioRing)); }
void ring_free(AudioRing *r) { free(r); }
void ring_push(AudioRing *r, const AudioBufferList *b, uint64_t host) {
    if (!r || !b || !b->mNumberBuffers || !b->mBuffers[0].mData) return;
    uint64_t w=atomic_load_explicit(&r->write,memory_order_relaxed);
    uint64_t rd=atomic_load_explicit(&r->read,memory_order_acquire);
    uint32_t channels=b->mBuffers[0].mNumberChannels;
    if (!channels) return;
    uint32_t n=b->mBuffers[0].mDataByteSize/(4*channels);
    if (!n) return;
    if (w-rd>=SLOTS || n>FRAMES) { atomic_fetch_add(&r->overflow,1); return; }
    Slot *s=&r->slots[w%SLOTS]; s->frames=n; s->host=host;
    if (b->mNumberBuffers==1 && channels==2) memcpy(s->data,b->mBuffers[0].mData,n*8);
    else if (b->mNumberBuffers==2 && channels==1 && b->mBuffers[1].mData && b->mBuffers[1].mDataByteSize>=n*4) {
        const float *a=b->mBuffers[0].mData, *c=b->mBuffers[1].mData;
        for (uint32_t i=0;i<n;i++) { s->data[2*i]=a[i]; s->data[2*i+1]=c[i]; }
    } else { atomic_fetch_add(&r->overflow,1); return; }
    atomic_store_explicit(&r->write,w+1,memory_order_release);
}
int ring_pop(AudioRing *r, float *dst, uint32_t *frames, uint64_t *host) {
    uint64_t rd=atomic_load_explicit(&r->read,memory_order_relaxed);
    if (rd==atomic_load_explicit(&r->write,memory_order_acquire)) return 0;
    Slot *s=&r->slots[rd%SLOTS]; *frames=s->frames; *host=s->host;
    memcpy(dst,s->data,s->frames*8);
    atomic_store_explicit(&r->read,rd+1,memory_order_release); return 1;
}
uint64_t ring_overflows(AudioRing *r) { return atomic_load(&r->overflow); }
