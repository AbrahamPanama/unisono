import app.unisono.PlaybackTuning;
public class PlaybackTuningTest {
    public static void main(String[] args) {
        PlaybackTuning t=new PlaybackTuning();
        if(t.bufferMs()!=100) throw new AssertionError("Startup buffer");
        for(int i=1;i<20;i++) { t.observeUnderruns(i); if(t.bufferMs()>250) throw new AssertionError("Unbounded buffer"); }
        if(t.bufferMs()!=250||t.observeUnderruns(19)) throw new AssertionError("Underrun growth");
        t=new PlaybackTuning(); float last=1;
        for(int i=0;i<100;i++) if(t.correction(i%2==0 ? 2 : -2,i*500_000_000L)!=1) throw new AssertionError("Chasing timestamp noise");
        long changed=0;
        for(int i=100;i<500;i++) {
            long now=i*500_000_000L; float next=t.correction(1000,now);
            if(next>1.00201f||next<0.99799f||Math.abs(next-last)>0.000201f) throw new AssertionError("Abrupt speed step");
            if(next!=last) { if(changed!=0&&now-changed<2_000_000_000L) throw new AssertionError("Frequent speed changes");changed=now; } last=next;
        }
        if(PlaybackTuning.initialReserve("balanced")!=250||PlaybackTuning.initialReserve("lossless")!=250||PlaybackTuning.initialReserve("stable")!=750) throw new AssertionError("Initial reserve policy");
        if(PlaybackTuning.nextReserve(250)!=500||PlaybackTuning.nextReserve(500)!=750||PlaybackTuning.nextReserve(750)!=1000||PlaybackTuning.nextReserve(1000)!=1000) throw new AssertionError("Reserve bounds");
        PlaybackTuning.Gain gain=new PlaybackTuning.Gain(); float[] data=new float[960];java.util.Arrays.fill(data,1);gain.apply(data,1,48000);
        for(int i=2;i<data.length;i+=2) if(data[i]-data[i-2]>1f/480+0.000001f) throw new AssertionError("Gain click");
        java.util.Arrays.fill(data,1);gain.apply(data,0,48000);
        for(int i=2;i<data.length;i+=2) if(data[i-2]-data[i]>1f/480+0.000001f) throw new AssertionError("Mute click");
        System.out.println("PASS: adaptive buffer bounds, reserve growth, clock deadband/slew, smooth gain.");
    }
}
