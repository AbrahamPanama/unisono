package app.unisono;

import android.app.*;
import android.content.*;
import android.graphics.*;
import android.os.*;
import android.view.*;
import android.widget.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import org.json.*;

/** Emulator-only manual-mode/diagnostics integration; never included in production APKs. */
public final class DebugTest extends Instrumentation {
    private String scenario;
    private static final String LINK="unisono://10.0.2.2:45871#11111111111111111111111111111111";
    @Override public void onCreate(Bundle args) { super.onCreate(args);scenario=args.getString("scenario","manual");start(); }
    private interface Check { boolean okay() throws Exception; }
    private void await(String what,Check check,int seconds) throws Exception {
        long deadline=System.nanoTime()+seconds*1_000_000_000L;
        while(System.nanoTime()<deadline) {if(check.okay())return;Thread.sleep(150);}
        throw new AssertionError(what+": "+AudioService.status+" / "+AudioDiagnostics.latest());
    }
    private Object field(Object target,String name) throws Exception {java.lang.reflect.Field f=target.getClass().getDeclaredField(name);f.setAccessible(true);return f.get(target);}
    private View find(View view,String text) {
        if(view instanceof TextView&&((TextView)view).getText().toString().equals(text))return view;
        if(view instanceof ViewGroup)for(int i=0;i<((ViewGroup)view).getChildCount();i++) {View match=find(((ViewGroup)view).getChildAt(i),text);if(match!=null)return match;}
        return null;
    }
    private void click(Activity a,String text) {runOnMainSync(()-> {View view=find(a.getWindow().getDecorView(),text);if(view==null)throw new AssertionError("Missing control "+text);view.performClick();});}
    private long frames() throws Exception {
        if(AudioService.instance==null||!AudioService.connected)throw new AssertionError("No active output");
        android.media.AudioTrack track=(android.media.AudioTrack)field(AudioService.instance,"track");
        if(track==null||track.getPlayState()!=android.media.AudioTrack.PLAYSTATE_PLAYING)throw new AssertionError("Output not playing");
        return Integer.toUnsignedLong(track.getPlaybackHeadPosition());
    }
    private void assertManual(int reserve,int buffer) throws Exception {
        JSONObject s=AudioDiagnostics.latest();
        if(!s.optBoolean("connected")||!"manual".equals(s.optString("mode"))||s.getInt("reserveMs")!=reserve||s.getInt("requestedBufferMs")!=buffer||Math.abs(s.getDouble("speed")-1)>0.000001)
            throw new AssertionError("Manual controls drifted: "+s);
        if(!"flac".equals(s.getString("codec")))throw new AssertionError("Manual codec changed");
    }
    private boolean event(String type) {
        for(JSONObject e:AudioDiagnostics.events())if(type.equals(e.optString("type")))return true;
        return false;
    }
    private void structuralChecks() throws Exception {
        DebugSettings settings=new DebugSettings(true,250,80,40,false,true);
        if(!settings.sameValues(DebugSettings.fromJson(settings.json())))throw new AssertionError("Settings round-trip");
        for(Object value:new Object[]{99,2001,250.5,true,"250"}) {
            JSONObject invalid=settings.json().put("reserveMs",value);boolean rejected=false;
            try {DebugSettings.fromJson(invalid);}catch(Exception expected){rejected=true;}
            if(!rejected)throw new AssertionError("Invalid reserve accepted: "+value);
        }
        for(String address:new String[]{"fe80::1","::1","2001:db8:0:0:0:0:0:1","fe80::1%wlan0","::ffff:192.0.2.1"}) {
            String redacted=AudioDiagnostics.safe("Socket failed for ["+address+"]:45871");
            if(redacted.contains(address)||redacted.contains("%wlan0")||redacted.contains("192.0.2.1"))throw new AssertionError("Address leaked: "+redacted);
        }
        AudioDiagnostics.clear();
        long testTime=SystemClock.elapsedRealtime();
        for(int i=0;i<610;i++)AudioDiagnostics.sample(new JSONObject().put("timeMs",System.currentTimeMillis()+i*1000L).put("monotonicMs",testTime+i*1000L).put("index",i));
        for(int i=0;i<210;i++)AudioDiagnostics.event("test","unisono://192.0.2.1:45871#11111111111111111111111111111111",settings);
        if(AudioDiagnostics.history().size()!=600||AudioDiagnostics.events().size()!=200)throw new AssertionError("Unbounded diagnostics");
        String json=AudioDiagnostics.exportJson();new JSONObject(json);
        if(json.contains("192.0.2.1")||json.contains("11111111111111111111111111111111")||json.contains("unisono://"))throw new AssertionError("Private pairing data leaked");
        JSONObject snapshot=AudioDiagnostics.latest();snapshot.put("mutation",true);
        if(AudioDiagnostics.latest().has("mutation"))throw new AssertionError("Mutable diagnostics snapshot");
        AudioDiagnostics.clear();
    }
    private void save(Activity a,String name) {
        runOnMainSync(()-> {try {
            View view=a.getWindow().getDecorView();Bitmap b=Bitmap.createBitmap(view.getWidth(),view.getHeight(),Bitmap.Config.ARGB_8888);view.draw(new Canvas(b));
            try(FileOutputStream out=new FileOutputStream(new File(a.getExternalFilesDir(null),name))){b.compress(Bitmap.CompressFormat.PNG,100,out);}
        }catch(Exception e){throw new RuntimeException(e);}});
    }
    @Override public void onStart() {
        Bundle result=new Bundle();
        try {
            structuralChecks();
            getTargetContext().getSharedPreferences("playback",Context.MODE_PRIVATE).edit().putString("quality","flac").putBoolean("mixWithOtherApps",true).commit();
            new DebugSettings(true,250,80,40,false,!"legacy".equals(scenario)).save(getTargetContext());
            Activity main=startActivitySync(new Intent(Intent.ACTION_VIEW,android.net.Uri.parse(LINK),getTargetContext(),MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            waitForIdleSync();click(main,"Conectar");
            if("legacy".equals(scenario)) {
                await("Legacy manual mismatch should stop",()->!AudioDiagnostics.events().isEmpty()&&AudioService.instance==null,15);
                if(AudioService.connected)throw new AssertionError("Legacy server silently overrode manual values");
                String log=AudioDiagnostics.exportJson();
                if(!log.toLowerCase(java.util.Locale.ROOT).contains("manual"))throw new AssertionError("Missing manual incompatibility explanation");
            } else {
                await("Manual connected",()->AudioDiagnostics.latest().optBoolean("connected")&&AudioDiagnostics.latest().optInt("reserveMs")==250,15);
                if("jitter".equals(scenario)) {
                    await("Manual retry recorded",()->AudioDiagnostics.latest().optInt("reconnects")>0,20);
                    await("Manual retry connected",()->AudioService.connected,15);Thread.sleep(2000);assertManual(250,80);
                    for(JSONObject sample:AudioDiagnostics.history())if(sample.optBoolean("connected")&&sample.optInt("reserveMs")!=250)throw new AssertionError("Manual recovery increased reserve");
                } else {
                    Thread.sleep(2200);assertManual(250,80);
                    Activity debug=startActivitySync(new Intent(getTargetContext(),DebugActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));waitForIdleSync();
                    long before=frames();click(debug,"Pausar vista para inspeccionar");Thread.sleep(2200);if(frames()-before<48000)throw new AssertionError("Pausing chart paused playback");
                    click(debug,"Reanudar vista en vivo");
                    DebugSettings previous=DebugSettings.load(getTargetContext());
                    EditText reserve=(EditText)field(debug,"reserve");
                    runOnMainSync(()->reserve.setText("99"));click(debug,"Aplicar y reiniciar audio");
                    if(!DebugSettings.load(getTargetContext()).sameValues(previous))throw new AssertionError("Invalid UI draft changed settings");
                    runOnMainSync(()->reserve.setText("350"));click(debug,"Aplicar y reiniciar audio");
                    await("Local apply/restart",()->AudioDiagnostics.latest().optBoolean("connected")&&AudioDiagnostics.latest().optInt("reserveMs")==350,15);
                    assertManual(350,80);
                    if(reserve.getError()!=null)throw new AssertionError("Valid UI input retained its validation error");
                    Wire wire=(Wire)field(AudioService.instance,"wire");
                    DebugSettings remote=new DebugSettings(true,425,100,50,false,true);
                    wire.send(14,new JSONObject().put("debug",remote.json()).toString().getBytes(StandardCharsets.UTF_8));
                    await("Authenticated remote apply/restart",()->AudioDiagnostics.latest().optBoolean("connected")&&AudioDiagnostics.latest().optInt("reserveMs")==425,15);
                    assertManual(425,100);
                    if(!DebugSettings.load(getTargetContext()).sameValues(remote))throw new AssertionError("Remote settings not persisted");
                    DebugSettings newest=new DebugSettings(true,450,100,50,false,true);
                    runOnMainSync(()->{
                        AudioService.applyDebugSettings(getTargetContext(),new DebugSettings(true,500,100,50,false,true));
                        AudioService.applyDebugSettings(getTargetContext(),newest);
                    });
                    await("Latest of rapid applies wins",()->AudioDiagnostics.latest().optBoolean("connected")&&AudioDiagnostics.latest().optInt("reserveMs")==450,15);
                    Thread.sleep(1500);assertManual(450,100);
                    if(!DebugSettings.load(getTargetContext()).sameValues(newest))throw new AssertionError("Rapid applies lost latest settings");
                    if(AudioDiagnostics.history().size()<6||AudioDiagnostics.events().size()<3)throw new AssertionError("History lost across reconfigure");
                    save(debug,"android-debug-controls.png");
                    String exported=AudioDiagnostics.exportJson();JSONObject snapshot=new JSONObject(exported);
                    if(snapshot.getJSONArray("samples").length()<6||exported.contains("11111111111111111111111111111111")||exported.contains("10.0.2.2"))throw new AssertionError("Export invalid/private");
                    try(FileOutputStream out=new FileOutputStream(new File(getTargetContext().getExternalFilesDir(null),"debug-test-export.json"))){out.write(exported.getBytes(StandardCharsets.UTF_8));}
                }
            }
            result.putString("result","PASS: latency debugging "+scenario+"; strict settings, bounded private telemetry, fixed manual values and visible lifecycle events");
            finish(Activity.RESULT_OK,result);
        } catch(Throwable e) {result.putString("result","FAIL: "+e);finish(Activity.RESULT_CANCELED,result);}
        finally {getTargetContext().stopService(new Intent(getTargetContext(),AudioService.class));DebugSettings.defaults().save(getTargetContext());}
    }
}
