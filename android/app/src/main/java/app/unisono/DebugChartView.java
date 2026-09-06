package app.unisono;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.view.View;
import org.json.JSONObject;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Small, dependency-free time-series chart. Null samples are gaps, never zeroes. */
public final class DebugChartView extends View {
    public static final class Series {
        final String key,label;
        final int color;
        public Series(String key,String label,int color) { this.key=key;this.label=label;this.color=color; }
    }
    private final Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG);
    private final String title,unit;
    private final Series[] series;
    private List<JSONObject> samples=new ArrayList<>();
    private final float density;
    public DebugChartView(Context context,String title,String unit,Series... series) {
        super(context);this.title=title;this.unit=unit;this.series=series;
        density=getResources().getDisplayMetrics().density;
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_YES);
        setFocusable(true);
        setContentDescription(title+": sin muestras todavía");
    }
    public void setSamples(List<JSONObject> values) {
        samples=new ArrayList<>(values);
        StringBuilder summary=new StringBuilder(title).append(". Últimos diez minutos. ");
        for(Series s:series) {
            double last=Double.NaN,min=Double.POSITIVE_INFINITY,max=Double.NEGATIVE_INFINITY;
            for(JSONObject sample:samples) { double value=number(sample,s.key);if(Double.isFinite(value)) { last=value;min=Math.min(min,value);max=Math.max(max,value); } }
            summary.append(s.label).append(": ");
            if(Double.isFinite(last)) summary.append(format(last)).append(" ").append(unit).append(", mínimo ").append(format(min)).append(", máximo ").append(format(max));
            else summary.append("sin datos");
            summary.append(". ");
        }
        setContentDescription(summary.toString());invalidate();
    }
    private static double number(JSONObject object,String key) {
        if(object==null||object.isNull(key)) return Double.NaN;
        Object value=object.opt(key);return value instanceof Number ? ((Number)value).doubleValue() : Double.NaN;
    }
    private String format(double value) { return String.format(Locale.US,Math.abs(value)>=100 ? "%.0f" : "%.1f",value); }
    private float dp(float value) { return density*value; }
    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float left=dp(51),right=getWidth()-dp(8),top=dp(17),bottom=getHeight()-dp(30);
        if(right<=left||bottom<=top) return;
        long end=0,first=Long.MAX_VALUE;for(JSONObject sample:samples) {long time=timestamp(sample);end=Math.max(end,time);first=Math.min(first,time);}
        long window=Math.min(600000,Math.max(60000,((end-first+59999)/60000)*60000));
        long start=end-window;
        double min=0,max=0;boolean any=false;
        for(JSONObject sample:samples) {
            if(timestamp(sample)<start) continue;
            for(Series s:series) { double value=number(sample,s.key);if(Double.isFinite(value)) { min=Math.min(min,value);max=Math.max(max,value);any=true; } }
        }
        if(max-min<1) max=min+1;
        double margin=(max-min)*0.09;max+=margin;if(min<0) min-=margin;
        paint.setTextSize(dp(10));paint.setStrokeWidth(dp(1));paint.setStyle(Paint.Style.FILL);
        for(int tick=0;tick<=4;tick++) {
            float y=top+(bottom-top)*tick/4;
            paint.setColor(Color.rgb(60,66,63));canvas.drawLine(left,y,right,y,paint);
            paint.setColor(Color.rgb(187,197,190));paint.setTextAlign(Paint.Align.RIGHT);
            canvas.drawText(format(max-(max-min)*tick/4),left-dp(7),y+dp(3),paint);
        }
        paint.setTextAlign(Paint.Align.LEFT);canvas.drawText(unit,left,dp(10),paint);
        canvas.drawText("−"+window/60000+" min",left,bottom+dp(20),paint);
        paint.setTextAlign(Paint.Align.CENTER);canvas.drawText("−"+window/2000+" s",(left+right)/2,bottom+dp(20),paint);
        paint.setTextAlign(Paint.Align.RIGHT);canvas.drawText("última muestra",right,bottom+dp(20),paint);
        if(!any) {
            paint.setColor(Color.rgb(187,197,190));paint.setTextSize(dp(12));paint.setTextAlign(Paint.Align.CENTER);
            canvas.drawText("Sin muestras disponibles",(left+right)/2,(top+bottom)/2,paint);return;
        }
        canvas.save();canvas.clipRect(left,top,right+dp(2),bottom);
        for(Series s:series) {
            paint.setColor(s.color);paint.setStrokeWidth(dp(1.8f));paint.setStyle(Paint.Style.STROKE);
            Path path=new Path();boolean connected=false;long lastTime=0;float lastX=0,lastY=0;
            for(JSONObject sample:samples) {
                long time=timestamp(sample);if(time<start) continue;
                double value=number(sample,s.key);
                if(!Double.isFinite(value)) { connected=false;continue; }
                float x=left+(right-left)*(time-start)/(float)window;
                float y=(float)(bottom-(value-min)/(max-min)*(bottom-top));
                if(connected&&time>lastTime&&time-lastTime<=2500) path.lineTo(x,y);else path.moveTo(x,y);
                connected=true;lastTime=time;lastX=x;lastY=y;
            }
            canvas.drawPath(path,paint);
            if(connected) { paint.setStyle(Paint.Style.FILL);canvas.drawCircle(lastX,lastY,dp(2.5f),paint); }
        }
        canvas.restore();paint.setStyle(Paint.Style.FILL);
    }
    private static long timestamp(JSONObject sample) {return sample.optLong("monotonicMs",sample.optLong("timeMs",0));}
}
