#include "../mac/AudioRing.h"
#include <assert.h>
#include <stdio.h>
int main(void) {
    AudioRing *r=ring_create(); assert(r);
    float input[8]={0,.1f,.2f,-.3f,1,-1,.5f,-.5f},out[8192];
    AudioBufferList b={.mNumberBuffers=1,.mBuffers={{.mNumberChannels=2,.mDataByteSize=sizeof(input),.mData=input}}};
    uint32_t n; uint64_t host;
    assert(!ring_pop(r,out,&n,&host));
    for(int i=0;i<128;i++) ring_push(r,&b,i+1);
    ring_push(r,&b,999); assert(ring_overflows(r)==1);
    for(int i=0;i<128;i++) { assert(ring_pop(r,out,&n,&host)); assert(n==4&&host==(uint64_t)i+1); for(int j=0;j<8;j++) assert(out[j]==input[j]); }
    assert(!ring_pop(r,out,&n,&host));
    struct { UInt32 count; AudioBuffer buffers[2]; } stereo={.count=2,.buffers={{1,16,input},{1,16,input+4}}};
    ring_push(r,(AudioBufferList*)&stereo,55); assert(ring_pop(r,out,&n,&host));
    for(int i=0;i<4;i++) { assert(out[i*2]==input[i]); assert(out[i*2+1]==input[i+4]); }
    ring_free(r); puts("PASS: FIFO, exact interleaved/planar PCM, bounded overflow, empty queue.");
}
