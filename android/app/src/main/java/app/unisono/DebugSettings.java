package app.unisono;

import android.content.Context;
import android.content.SharedPreferences;
import org.json.JSONObject;

/** Requested controls. Effective hardware values are reported separately in diagnostics. */
public final class DebugSettings {
    public final boolean manual, clockCorrection, retry;
    public final int reserveMs, bufferMs, prefillMs;
    public DebugSettings(boolean manual,int reserveMs,int bufferMs,int prefillMs,boolean clockCorrection,boolean retry) {
        if(reserveMs<100||reserveMs>2000) throw new IllegalArgumentException("La reserva debe estar entre 100 y 2000 ms");
        if(bufferMs<20||bufferMs>500) throw new IllegalArgumentException("El búfer solicitado debe estar entre 20 y 500 ms");
        if(prefillMs<10||prefillMs>200||prefillMs>bufferMs) throw new IllegalArgumentException("El llenado inicial debe estar entre 10 y 200 ms y no superar el búfer solicitado");
        this.manual=manual;this.reserveMs=reserveMs;this.bufferMs=bufferMs;this.prefillMs=prefillMs;this.clockCorrection=clockCorrection;this.retry=retry;
    }
    public static DebugSettings defaults() { return new DebugSettings(false,750,100,100,true,true); }
    public static DebugSettings load(Context context) {
        SharedPreferences p=context.getSharedPreferences("latencyDebug",Context.MODE_PRIVATE);
        try { return new DebugSettings(p.getBoolean("manual",false),p.getInt("reserveMs",750),p.getInt("bufferMs",100),p.getInt("prefillMs",100),p.getBoolean("clockCorrection",true),p.getBoolean("retry",true)); }
        catch(RuntimeException e) { return defaults(); }
    }
    public void save(Context context) {
        context.getSharedPreferences("latencyDebug",Context.MODE_PRIVATE).edit().putBoolean("manual",manual).putInt("reserveMs",reserveMs).putInt("bufferMs",bufferMs).putInt("prefillMs",prefillMs).putBoolean("clockCorrection",clockCorrection).putBoolean("retry",retry).apply();
    }
    public JSONObject json() {
        JSONObject j=new JSONObject();
        try { j.put("manual",manual).put("reserveMs",reserveMs).put("bufferMs",bufferMs).put("prefillMs",prefillMs).put("clockCorrection",clockCorrection).put("retry",retry); }
        catch(Exception impossible) { throw new IllegalStateException(impossible); }
        return j;
    }
    private static int integer(JSONObject j,String key) throws Exception {
        Object value=j.get(key);
        if(!(value instanceof Number)||!Double.isFinite(((Number)value).doubleValue())||((Number)value).doubleValue()!=((Number)value).intValue()) throw new IllegalArgumentException("Valor entero no válido: "+key);
        return ((Number)value).intValue();
    }
    private static boolean bool(JSONObject j,String key) throws Exception {
        Object value=j.get(key); if(!(value instanceof Boolean)) throw new IllegalArgumentException("Valor booleano no válido: "+key);return (Boolean)value;
    }
    public static DebugSettings fromJson(JSONObject j) throws Exception {
        return new DebugSettings(bool(j,"manual"),integer(j,"reserveMs"),integer(j,"bufferMs"),integer(j,"prefillMs"),bool(j,"clockCorrection"),bool(j,"retry"));
    }
    public boolean sameValues(DebugSettings other) { return other!=null&&manual==other.manual&&reserveMs==other.reserveMs&&bufferMs==other.bufferMs&&prefillMs==other.prefillMs&&clockCorrection==other.clockCorrection&&retry==other.retry; }
}
