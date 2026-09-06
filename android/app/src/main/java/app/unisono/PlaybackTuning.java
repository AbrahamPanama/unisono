package app.unisono;

/** Pure playback policy, independent of Android so its bounds can be regression-tested. */
public final class PlaybackTuning {
    private int bufferMs=100, lastUnderruns;
    private double filteredError;
    private float speed=1;
    private long lastChange;
    public int bufferMs() { return bufferMs; }
    public boolean observeUnderruns(int count) {
        if(count<=lastUnderruns) return false;
        lastUnderruns=count; int old=bufferMs; bufferMs=Math.min(250,bufferMs+40); return old!=bufferMs;
    }
    // Do not chase timestamp noise. Slow, infrequent changes avoid repeatedly disturbing
    // the device's time-stretch path. Buffer growth is never undone mid-session.
    public float correction(double errorMs,long now) {
        if(!Double.isFinite(errorMs)) return speed;
        filteredError=0.9*filteredError+0.1*errorMs;
        if(now-lastChange<2_000_000_000L) return speed;
        float target=Math.abs(filteredError)<3 ? 1 : (float)(1+Math.max(-0.002,Math.min(0.002,filteredError/40000)));
        float next=speed+Math.max(-0.0002f,Math.min(0.0002f,target-speed));
        if(Math.abs(next-speed)>=0.00005f) { speed=next; lastChange=now; }
        return speed;
    }
    // The Mac's configured reserve still supplies the normal 500 ms default.
    // Balanced AAC and PCM permit a smaller explicit Mac setting; stable AAC keeps its floor.
    public static int initialReserve(String quality) { return "stable".equals(quality) ? 750 : 250; }
    public static int nextReserve(int current) { return Math.min(1000,Math.max(250,current)+250); }
    public static final class Gain {
        private float current;
        public void apply(float[] samples,float target,int rate) {
            target=Float.isFinite(target) ? Math.max(0,Math.min(1,target)) : 0; float step=1f/Math.max(1,rate/100);
            for(int f=0;f<samples.length;f+=2) {
                current+=Math.max(-step,Math.min(step,target-current));
                for(int c=f;c<Math.min(f+2,samples.length);c++) samples[c]=Float.isFinite(samples[c]) ? samples[c]*current : 0;
            }
        }
    }
}
