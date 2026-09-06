package app.unisono;

import android.os.SystemClock;
import org.json.JSONArray;
import org.json.JSONObject;
import java.util.ArrayList;
import java.util.List;

/** In-memory, bounded diagnostics only: no audio, addresses, pairing links or device names. */
public final class AudioDiagnostics {
    private static final ArrayList<JSONObject> samples=new ArrayList<>(), log=new ArrayList<>();
    private static JSONObject current=new JSONObject();
    private static JSONObject copy(JSONObject value) { try { return new JSONObject(value.toString()); } catch(Exception e) { return new JSONObject(); } }
    public static synchronized JSONObject latest() { return copy(current); }
    public static synchronized List<JSONObject> history() { ArrayList<JSONObject> out=new ArrayList<>();for(JSONObject j:samples) out.add(copy(j));return out; }
    public static synchronized List<JSONObject> events() { ArrayList<JSONObject> out=new ArrayList<>();for(JSONObject j:log) out.add(copy(j));return out; }
    public static synchronized void clear() { samples.clear();log.clear(); }
    static synchronized void sample(JSONObject value) {
        // Connection transitions may update latest immediately; retain observed peaks when
        // coalescing those updates into a single chart point for the monotonic second.
        if(!samples.isEmpty()&&samples.get(samples.size()-1).optLong("monotonicMs")/1000==value.optLong("monotonicMs")/1000) {
            JSONObject previous=samples.get(samples.size()-1);
            for(String key:new String[]{"packetGapMaxMs","decodeMaxMs","writeMaxMs","arrivalAgeMaxMs"}) {
                double old=previous.optDouble(key,Double.NaN),next=value.optDouble(key,Double.NaN);
                if(Double.isFinite(old)&&(!Double.isFinite(next)||old>next))try {value.put(key,old);}catch(Exception ignored){}
            }
            samples.set(samples.size()-1,copy(value));
        } else samples.add(copy(value));
        current=copy(value);
        long cutoff=SystemClock.elapsedRealtime()-600_000;
        while(!samples.isEmpty()&&(samples.size()>600||samples.get(0).optLong("monotonicMs")<cutoff)) samples.remove(0);
    }
    static void event(String type,String detail,DebugSettings settings) {
        eventAt(type,detail,settings,System.currentTimeMillis(),SystemClock.elapsedRealtime());
    }
    static synchronized void eventAt(String type,String detail,DebugSettings settings,long wallTime,long monotonicTime) {
        try {
            JSONObject j=new JSONObject().put("timeMs",wallTime).put("monotonicMs",monotonicTime).put("type",type).put("detail",safe(detail));
            j.put("settings",settings==null?JSONObject.NULL:settings.json());
            JSONObject observed=current;
            if(current.optLong("monotonicMs",Long.MAX_VALUE)>monotonicTime) {
                observed=new JSONObject();
                for(int i=samples.size()-1;i>=0;i--)if(samples.get(i).optLong("monotonicMs",Long.MAX_VALUE)<=monotonicTime) {observed=samples.get(i);break;}
            }
            JSONObject values=new JSONObject();
            for(String key:new String[]{"timeMs","connected","reserveMs","requestedBufferMs","bufferMs","prefillMs","syncMs","speed","underruns","totalUnderruns","queueMs","queuedOutputMs","codec","bitrateKbps","packetGapMaxMs","decodeMaxMs","writeMaxMs","arrivalAgeMaxMs"}) values.put(key,observed.has(key)?observed.opt(key):JSONObject.NULL);
            j.put("lastSample",values);int position=log.size();while(position>0&&log.get(position-1).optLong("monotonicMs")>monotonicTime)position--;log.add(position,j);while(log.size()>200)log.remove(0);
        } catch(Exception ignored) {}
    }
    static String safe(String text) {
        if(text==null)return "";
        // Includes compressed/scoped IPv6 and peer details in platform exception strings.
        String redacted=text.replaceAll("(?i)unisono://\\S+","[enlace privado]")
            .replaceAll("\\b(?:\\d{1,3}\\.){3}\\d{1,3}(?::\\d+)?\\b","[dirección]")
            .replaceAll("(?i)(?<![a-z0-9])(?:[0-9a-f]{0,4}:){2,}[0-9a-f:]{0,39}(?:%[a-z0-9_.~-]+)?(?![a-z0-9])","[dirección]")
            .replaceAll("(?i)\\b[0-9a-f]{32,}\\b","[clave privada]")
            .replaceAll("/Users/[^/\\s]+","/Users/[usuario]");
        return redacted.substring(0,Math.min(300,redacted.length()));
    }
    public static synchronized String exportJson() {
        try {
            return new JSONObject().put("schema",1).put("exportedAtMs",System.currentTimeMillis()).put("notes","Software estimates only; no measured acoustic latency. Jitter is audio-message arrival variation over TCP, not packet loss. Output queue is a source-frame estimate. RTT is measured without changing the session clock alignment.").put("latest",copy(current)).put("samples",new JSONArray(history())).put("events",new JSONArray(events())).toString(2);
        } catch(Exception e) { return "{}"; }
    }
}
