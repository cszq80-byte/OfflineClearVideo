package com.offline.clearvideo;

import android.app.Activity;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.media.MediaMetadataRetriever;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.MotionEvent;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Entirely offline A/B inspection of matching original and processed video frames.
 * Original is left, processed is right. Drag the divider, select a time, and zoom
 * into skin, hair, stitching or cloth folds. No frames are uploaded or retained.
 */
public final class FrameComparisonActivity extends Activity {
    private final Handler ui=new Handler(Looper.getMainLooper());
    private final ExecutorService queue=Executors.newSingleThreadExecutor();
    private final AtomicInteger latestRequest=new AtomicInteger();
    private MediaMetadataRetriever original,enhanced;
    private long durationUs;
    private CompareView compare;
    private TextView status;
    private volatile boolean closed;
    private int dp(float v){return Math.round(v*getResources().getDisplayMetrics().density);}
    private TextView label(String text,int size){
        TextView t=new TextView(this);t.setText(text);t.setTextColor(Color.WHITE);t.setTextSize(size);
        return t;
    }
    @Override protected void onCreate(Bundle savedInstanceState){
        super.onCreate(savedInstanceState);
        getWindow().setStatusBarColor(Color.rgb(12,19,32));
        ScrollView scroll=new ScrollView(this);
        scroll.setBackgroundColor(Color.rgb(12,19,32));
        LinearLayout root=new LinearLayout(this);
        root.setPadding(dp(14),dp(18),dp(14),dp(20));
        root.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(root);
        setContentView(scroll);
        TextView title=label("原片 / AI 结果逐帧对比",21);root.addView(title);
        TextView tip=label("左侧原片 · 右侧处理后 | 拖动分割线，放大检查皮肤或布料",12);
        tip.setTextColor(Color.LTGRAY);
        root.addView(tip);
        compare=new CompareView();
        LinearLayout.LayoutParams cmpParams=new LinearLayout.LayoutParams(-1,dp(420));
        cmpParams.topMargin=dp(14);
        root.addView(compare,cmpParams);
        status=label("正在读取两段本地视频…",13);
        LinearLayout.LayoutParams st=new LinearLayout.LayoutParams(-1,-2);st.topMargin=dp(9);
        root.addView(status,st);
        TextView time=label("时间位置：",13);root.addView(time);
        SeekBar seek=new SeekBar(this);seek.setMax(1000);
        seek.setProgress(0);root.addView(seek);
        seek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener(){
            @Override public void onProgressChanged(SeekBar bar,int p,boolean fromUser){
                long seconds=durationUs*p/1000/1000000;
                time.setText("视频时间："+seconds+" 秒");
            }
            @Override public void onStartTrackingTouch(SeekBar bar){}
            @Override public void onStopTrackingTouch(SeekBar bar){requestFrame(bar.getProgress());}
        });
        TextView zoomLabel=label("同步局部放大：1.0 倍",13);root.addView(zoomLabel);
        SeekBar zoom=new SeekBar(this);zoom.setMax(30);zoom.setProgress(0);root.addView(zoom);
        zoom.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener(){
            @Override public void onProgressChanged(SeekBar bar,int p,boolean fromUser){
                float value=1+p/10f;
                compare.setZoom(value);
                zoomLabel.setText(String.format(java.util.Locale.ROOT,"同步局部放大：%.1f 倍",value));
            }
            @Override public void onStartTrackingTouch(SeekBar bar){}
            @Override public void onStopTrackingTouch(SeekBar bar){}
        });
        Button reset=new Button(this);reset.setAllCaps(false);reset.setText("回到原始比例 / 分割线居中");
        root.addView(reset);reset.setOnClickListener(v->{zoom.setProgress(0);compare.reset();});
        try {
            String input=getIntent().getStringExtra("input");
            String output=getIntent().getStringExtra("output");
            if(input==null||output==null){
                android.content.SharedPreferences p=getSharedPreferences("processing_state",MODE_PRIVATE);
                input=p.getString("last_input",null);
                output=p.getString("last_output",null);
            }
            if(input==null||output==null)throw new IllegalStateException("还没有成功导出的视频");
            original=new MediaMetadataRetriever();enhanced=new MediaMetadataRetriever();
            original.setDataSource(this,Uri.parse(input));
            enhanced.setDataSource(this,Uri.parse(output));
            durationUs=Math.min(duration(original),duration(enhanced));
            requestFrame(0);
        }catch(Exception e){
            status.setText("无法读取视频："+e.getMessage()+"。可能是相册授权已过期，请重新选片处理。");
        }
    }
    private static long duration(MediaMetadataRetriever m){
        String s=m.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION);
        try{return Math.max(1,Long.parseLong(s))*1000L;}
        catch(Exception e){return 1000000L;}
    }
    private void requestFrame(int position){
        if(original==null||enhanced==null||closed)return;
        int id=latestRequest.incrementAndGet();
        long timeUs=Math.max(0,Math.min(durationUs-1000,durationUs*position/1000));
        status.setText("正在本地读取画面，请勿退出…");
        queue.execute(()->{
            Bitmap a=null,b=null;
            try {
                a=frame(original,timeUs);b=frame(enhanced,timeUs);
                if(a==null||b==null)throw new IllegalStateException("当前时间点没有可解码的画面");
                Bitmap oa=a,ob=b;
                ui.post(()->{
                    if(closed||id!=latestRequest.get()){
                        oa.recycle();ob.recycle();return;
                    }
                    compare.setFrames(oa,ob);
                    status.setText("左原片 / 右增强：拖动中线对比；放大后拖动画面定位皮肤或衣服细节。");
                });
            }catch(Exception e){
                if(a!=null&&!a.isRecycled())a.recycle();
                if(b!=null&&!b.isRecycled())b.recycle();
                String message=e.getMessage();
                ui.post(()->{if(!closed&&id==latestRequest.get())
                    status.setText("读取失败："+message);});
            }
        });
    }
    private static Bitmap frame(MediaMetadataRetriever retriever,long timeUs) {
        int w=0,h=0;
        try {
            w=Integer.parseInt(retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH));
            h=Integer.parseInt(retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT));
        }catch(Exception ignored){}
        if(w>0&&h>0){
            float scale=Math.min(1f,1920f/Math.max(w,h));
            int dw=Math.max(2,Math.round(w*scale)),dh=Math.max(2,Math.round(h*scale));
            if(android.os.Build.VERSION.SDK_INT>=27)
                return retriever.getScaledFrameAtTime(timeUs,MediaMetadataRetriever.OPTION_CLOSEST,dw,dh);
            // Android 8.0 (API 26) lacks getScaledFrameAtTime.
            Bitmap full=retriever.getFrameAtTime(timeUs,MediaMetadataRetriever.OPTION_CLOSEST);
            if(full==null||scale>=1f)return full;
            Bitmap resized=Bitmap.createScaledBitmap(full,dw,dh,true);
            if(resized!=full)full.recycle();
            return resized;
        }
        return retriever.getFrameAtTime(timeUs,MediaMetadataRetriever.OPTION_CLOSEST);
    }
    @Override protected void onDestroy(){
        closed=true;
        latestRequest.incrementAndGet();
        queue.execute(()->{
            if(original!=null)try{original.release();}catch(Exception ignored){}
            if(enhanced!=null)try{enhanced.release();}catch(Exception ignored){}
        });
        queue.shutdown();
        if(compare!=null)compare.clear();
        super.onDestroy();
    }
    private final class CompareView extends View {
        private Bitmap before,after;
        private final Paint imagePaint=new Paint(Paint.FILTER_BITMAP_FLAG|Paint.ANTI_ALIAS_FLAG);
        private final Paint guide=new Paint(Paint.ANTI_ALIAS_FLAG);
        private float split=.5f,zoom=1f,focusX=.5f,focusY=.5f;
        private boolean moveSplit;
        private float lastX,lastY;
        CompareView(){super(FrameComparisonActivity.this);setBackgroundColor(Color.rgb(26,35,48));}
        void setFrames(Bitmap a,Bitmap b){
            Bitmap oldA=before,oldB=after;before=a;after=b;
            if(oldA!=null&&!oldA.isRecycled())oldA.recycle();
            if(oldB!=null&&!oldB.isRecycled())oldB.recycle();
            invalidate();
        }
        void setZoom(float z){zoom=Math.max(1,Math.min(4,z));invalidate();}
        void reset(){split=.5f;focusX=focusY=.5f;invalidate();}
        void clear(){
            if(before!=null&&!before.isRecycled())before.recycle();
            if(after!=null&&!after.isRecycled())after.recycle();
            before=after=null;
        }
        @Override protected void onDraw(Canvas canvas){
            super.onDraw(canvas);
            int width=getWidth(),height=getHeight();
            if(width<=0||height<=0)return;
            if(before==null||after==null){
                guide.setColor(Color.LTGRAY);guide.setTextSize(dp(14));
                canvas.drawText("选取时间点后显示局部对比",dp(12),height/2f,guide);
                return;
            }
            // Both images map onto the same common scene coordinates.
            float aspectW=after.getWidth(),aspectH=after.getHeight();
            float base=Math.min(width/aspectW,height/aspectH);
            float dw=aspectW*base*zoom,dh=aspectH*base*zoom;
            float left=width*.5f-focusX*dw,top=height*.5f-focusY*dh;
            RectF dst=new RectF(left,top,left+dw,top+dh);
            int boundary=Math.round(split*width);
            canvas.save();
            canvas.clipRect(0,0,boundary,height);
            canvas.drawBitmap(before,null,dst,imagePaint);
            canvas.restore();
            canvas.save();
            canvas.clipRect(boundary,0,width,height);
            canvas.drawBitmap(after,null,dst,imagePaint);
            canvas.restore();
            guide.setColor(Color.rgb(78,219,201));guide.setStrokeWidth(dp(2));
            canvas.drawLine(boundary,0,boundary,height,guide);
            guide.setColor(Color.WHITE);guide.setTextSize(dp(13));guide.setShadowLayer(dp(3),0,0,Color.BLACK);
            canvas.drawText("原片",dp(12),dp(23),guide);
            canvas.drawText("AI 增强",Math.max(boundary+dp(8),width-dp(95)),dp(23),guide);
            guide.clearShadowLayer();
        }
        @Override public boolean onTouchEvent(MotionEvent event){
            float x=event.getX(),y=event.getY();
            int action=event.getActionMasked();
            if(action==MotionEvent.ACTION_DOWN){
                moveSplit=Math.abs(x-split*getWidth())<dp(38)||zoom<=1.05f;
                lastX=x;lastY=y;return true;
            }
            if(action==MotionEvent.ACTION_MOVE){
                if(moveSplit){
                    split=Math.max(.05f,Math.min(.95f,x/Math.max(1f,getWidth())));
                }else if(after!=null){
                    float base=Math.min(getWidth()/(float)after.getWidth(),
                        getHeight()/(float)after.getHeight())*zoom;
                    focusX=Math.max(0f,Math.min(1f,focusX-(x-lastX)/(after.getWidth()*base)));
                    focusY=Math.max(0f,Math.min(1f,focusY-(y-lastY)/(after.getHeight()*base)));
                }
                lastX=x;lastY=y;invalidate();return true;
            }
            return action==MotionEvent.ACTION_UP||action==MotionEvent.ACTION_CANCEL;
        }
    }
}
