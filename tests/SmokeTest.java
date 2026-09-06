package app.unisono;
import android.app.*;
import android.os.*;
import android.content.*;
import android.graphics.*;
import android.view.*;
import android.widget.*;
import java.io.*;

/** Installed only in the test APK; exercises the real Activity, service, socket and AudioTrack. */
public class SmokeTest extends Instrumentation {
    private volatile Boolean fixturePlaying;
    private boolean stability,noFlac;
    private String selectedQuality;
    private int expectedReserve;
    private final BroadcastReceiver fixtureState=new BroadcastReceiver() {
        @Override public void onReceive(Context c,Intent i) { fixturePlaying=i.getBooleanExtra("playing",false); }
    };
    private void fixture(String mode,boolean expectPlaying) throws Exception {
        fixturePlaying=null;
        getTargetContext().startActivity(new Intent().setComponent(new ComponentName("app.unisono.focusfixture","app.unisono.focusfixture.PlayerActivity")).putExtra("mode",mode).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        long end=System.nanoTime()+5_000_000_000L;
        while(fixturePlaying==null && System.nanoTime()<end) Thread.sleep(100);
        if(fixturePlaying==null || fixturePlaying!=expectPlaying) throw new AssertionError("Independent player failed: "+mode+" / "+fixturePlaying);
    }
    private long frames() throws Exception {
        AudioService service=AudioService.instance;
        if(service==null || !AudioService.connected) throw new AssertionError("Audio service stopped: "+AudioService.status);
        java.lang.reflect.Field field=AudioService.class.getDeclaredField("track"); field.setAccessible(true);
        android.media.AudioTrack track=(android.media.AudioTrack)field.get(service);
        if(track==null || track.getPlayState()!=android.media.AudioTrack.PLAYSTATE_PLAYING) throw new AssertionError("AudioTrack not playing");
        return Integer.toUnsignedLong(track.getPlaybackHeadPosition());
    }
    private void advancing() throws Exception { long before=frames(); Thread.sleep(2200); if(frames()-before<48000) throw new AssertionError("Playback did not advance in background"); }
    private int reserve() throws Exception {
        AudioService service=AudioService.instance;
        if(service==null) return -1;
        java.lang.reflect.Field field=AudioService.class.getDeclaredField("delayMs"); field.setAccessible(true);
        return field.getInt(service);
    }
    private boolean recovered(int minimumReserve) throws Exception {
        boolean codecMatches="AAC".equals(expectedCodec()) ? AudioService.details.startsWith("AAC")&&AudioService.details.contains("160 kbps") : AudioService.details.startsWith(expectedCodec());
        return AudioService.connected && codecMatches && reserve()>=minimumReserve;
    }
    private String expectedCodec() { return "lossless".equals(selectedQuality)||("flac".equals(selectedQuality)&&noFlac) ? "PCM" : "flac".equals(selectedQuality) ? "FLAC" : "AAC"; }
    private void assertMixer() throws Exception {
        String dump;
        try(ParcelFileDescriptor fd=getUiAutomation().executeShellCommand("dumpsys audio"); FileInputStream in=new FileInputStream(fd.getFileDescriptor()); ByteArrayOutputStream out=new ByteArrayOutputStream()) {
            byte[] buf=new byte[4096]; int n; while((n=in.read(buf))!=-1) out.write(buf,0,n); dump=out.toString("UTF-8");
        }
        int other=getTargetContext().getPackageManager().getApplicationInfo("app.unisono.focusfixture",0).uid;
        for(int uid:new int[]{android.os.Process.myUid(),other}) {
            boolean audible=false;
            for(String line:dump.split("\n")) if(line.contains("type:android.media.AudioTrack") && line.contains("u/pid:"+uid+"/") && line.contains("state:started") && line.contains("mutedState:none")) audible=true;
            if(!audible) throw new AssertionError("Mixer has no unmuted active AudioTrack for UID "+uid);
        }
        if(!dump.matches("(?s).*faded out players piids:\\s*muted player piids due to call/ring:.*")) throw new AssertionError("Mixer reports faded players");
        try(FileOutputStream out=new FileOutputStream(new File(getTargetContext().getExternalFilesDir(null),"audio-mixing-state.txt"))) { out.write(dump.getBytes("UTF-8")); }
    }
    private void awaitConnection() throws Exception {
        long end=System.nanoTime()+12_000_000_000L;
        while(!AudioService.connected && System.nanoTime()<end) Thread.sleep(200);
        if(!AudioService.connected) throw new AssertionError("Connection failed: "+AudioService.status);
        Thread.sleep(1000);
    }
    @Override public void onCreate(Bundle args) {
        super.onCreate(args); stability="true".equals(args.getString("stability")); noFlac="true".equals(args.getString("noFlac")); selectedQuality=args.getString("quality","balanced");
        int macReserve=Integer.parseInt(args.getString("reserve","500"));
        expectedReserve=Math.max(Math.max(250,Math.min(1000,macReserve)),"stable".equals(selectedQuality) ? 750 : 250);
        start();
    }
    private View find(View v,String text) {
        if(v instanceof Button && ((Button)v).getText().toString().equals(text)) return v;
        if(v instanceof ViewGroup) { ViewGroup g=(ViewGroup)v; for(int i=0;i<g.getChildCount();i++) { View hit=find(g.getChildAt(i),text); if(hit!=null) return hit; } } return null;
    }
    private void save(Activity a,String name) {
        runOnMainSync(()-> { try { View v=a.getWindow().getDecorView(); Bitmap b=Bitmap.createBitmap(v.getWidth(),v.getHeight(),Bitmap.Config.ARGB_8888); v.draw(new Canvas(b)); File f=new File(a.getExternalFilesDir(null),name); try(FileOutputStream o=new FileOutputStream(f)) { b.compress(Bitmap.CompressFormat.PNG,100,o); } } catch(Exception e) { throw new RuntimeException(e); } });
    }
    @Override public void onStart() {
        Bundle result=new Bundle();
        try {
            DebugSettings.defaults().save(getTargetContext());
            if(Build.VERSION.SDK_INT>=33) getTargetContext().registerReceiver(fixtureState,new IntentFilter("app.unisono.TEST_PLAYER_STATE"),Context.RECEIVER_EXPORTED);
            else getTargetContext().registerReceiver(fixtureState,new IntentFilter("app.unisono.TEST_PLAYER_STATE"));
            getTargetContext().getSharedPreferences("playback",Context.MODE_PRIVATE).edit().putBoolean("mixWithOtherApps",true).putString("quality",selectedQuality).commit();
            if(!stability) {
                if("flac".equals(selectedQuality)&&!noFlac) FlacRoundTrip.run("unisono://10.0.2.2:45871#11111111111111111111111111111111");
                else AacRoundTrip.run("unisono://10.0.2.2:45871#11111111111111111111111111111111");
            }
            Intent intent=new Intent(Intent.ACTION_VIEW,android.net.Uri.parse("unisono://10.0.2.2:45871#11111111111111111111111111111111"),getTargetContext(),MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            Activity a=startActivitySync(intent); waitForIdleSync(); Thread.sleep(1000); save(a,"android-idle.png");
            runOnMainSync(()-> { View b=find(a.getWindow().getDecorView(),"Conectar"); if(b==null) throw new AssertionError("Connect button missing"); b.performClick(); });
            awaitConnection();
            if(reserve()!=expectedReserve) throw new AssertionError("Negotiated reserve: expected "+expectedReserve+" ms, got "+reserve()+" ms; "+AudioService.details);
            if(stability) {
                int recoveryReserve=Math.min(1000,expectedReserve+250);
                long deadline=System.nanoTime()+25_000_000_000L;
                while(System.nanoTime()<deadline && !recovered(recoveryReserve)) Thread.sleep(200);
                if(!recovered(recoveryReserve)) throw new AssertionError("No adaptive recovery to at least "+recoveryReserve+" ms: "+AudioService.status+" / "+AudioService.details);
                Thread.sleep(2000); advancing();
                getTargetContext().stopService(new Intent(getTargetContext(),AudioService.class));
                result.putString("stream","PASS: audio playback recovery from an 850 ms network stall; "+AudioService.details); finish(Activity.RESULT_OK,result);return;
            }
            advancing(); if(!AudioService.details.startsWith(expectedCodec())) throw new AssertionError("Wrong codec selected; expected "+expectedCodec()+": "+AudioService.details); save(a,"android-connected.png");
            fixture("silent",false); advancing();
            fixture("play",true); advancing();
            if(a.hasWindowFocus()) throw new AssertionError("Receiver Activity did not enter background");
            fixture("query",true); assertMixer(); // Both players still active after competing GAIN.
            getTargetContext().stopService(new Intent(getTargetContext(),AudioService.class));
            Thread.sleep(1000); if(AudioService.connected||AudioService.connecting) throw new AssertionError("Service did not stop");
            // Reverse order: joining an already playing app must not steal its focus.
            getTargetContext().startActivity(new Intent(getTargetContext(),MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_REORDER_TO_FRONT));
            Thread.sleep(500);
            getTargetContext().startForegroundService(new Intent(getTargetContext(),AudioService.class).putExtra("link",intent.getData().toString()));
            awaitConnection(); advancing(); fixture("query",true); advancing(); assertMixer();
            getTargetContext().stopService(new Intent(getTargetContext(),AudioService.class)); Thread.sleep(700);
            // Normal mode still respects another music app's focus request.
            fixture("silent",false);
            getTargetContext().getSharedPreferences("playback",Context.MODE_PRIVATE).edit().putBoolean("mixWithOtherApps",false).commit();
            getTargetContext().startActivity(new Intent(getTargetContext(),MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)); Thread.sleep(500);
            getTargetContext().startForegroundService(new Intent(getTargetContext(),AudioService.class).putExtra("link",intent.getData().toString()));
            awaitConnection(); advancing(); fixture("play",true);
            long stoppedBy=System.nanoTime()+7_000_000_000L;
            while(AudioService.instance!=null && System.nanoTime()<stoppedBy) Thread.sleep(100);
            if(AudioService.connected||AudioService.connecting||AudioService.instance!=null) throw new AssertionError("Normal mode did not release focus");
            fixture("silent",false);
            getTargetContext().getSharedPreferences("playback",Context.MODE_PRIVATE).edit().putBoolean("mixWithOtherApps",true).commit();
            getTargetContext().unregisterReceiver(fixtureState);
            result.putString("stream","PASS: audio playback with "+("flac".equals(selectedQuality)&&!noFlac ? "FLAC exact 24-bit round-trip" : "AAC round-trip")+" validation, actual codec "+expectedCodec()+", background Activity, mixing in both start orders with a separate-UID media player, normal-mode focus loss, explicit disconnect."); finish(Activity.RESULT_OK,result);
        } catch(Throwable e) { result.putString("stream","FAIL: "+e.toString()+"; "+AudioService.status); finish(Activity.RESULT_CANCELED,result); }
    }
}
