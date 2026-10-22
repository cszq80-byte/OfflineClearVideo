package com.offline.clearvideo;
import android.app.Activity;
import android.os.Bundle;
import android.content.Intent;
import android.content.ActivityNotFoundException;
import android.os.Build;
import android.provider.MediaStore;
import android.content.res.ColorStateList;
import android.database.Cursor;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.media.MediaMetadataRetriever;
import android.net.Uri;
import android.provider.OpenableColumns;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.*;
import android.os.Handler;
import android.os.Looper;
import android.content.pm.PackageManager;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public final class MainActivity extends Activity {
  private static final int OPEN_FILE=7001,SAVE=7002,OPEN_GALLERY=7003;
  private final Handler progressHandler = new Handler(Looper.getMainLooper());
  private boolean polling;
  private ProcessingService.Snapshot lastSnapshot;
  private final int bg=Color.rgb(12,19,32),panel=Color.rgb(26,37,54),teal=Color.rgb(78,219,201);
  private Uri video;
  private final Runnable observer = new Runnable() {
    @Override public void run() {
      if(!polling)return;
      ProcessingService.Snapshot snapshot=ProcessingService.snapshot();
      boolean active=snapshot.running;
      if(choose!=null)choose.setEnabled(!active);
      if(browse!=null)browse.setEnabled(!active);
      if(run!=null)run.setEnabled(!active);
      if(stop!=null)stop.setEnabled(active);
      if(lastSnapshot!=snapshot){
        lastSnapshot=snapshot;
        if(status!=null&&!"尚未开始".equals(snapshot.message))status.setText(snapshot.message);
        if(progress!=null)progress.setProgress(snapshot.progress);
      }
      progressHandler.postDelayed(this,650);
    }
  };
  private TextView status,info;
  private ProgressBar progress;
  private Button choose,browse,run,stop;
  private RadioGroup modes;
  private VideoView preview;
  private int px(float dp){return (int)(getResources().getDisplayMetrics().density*dp+0.5f);}
  private TextView text(String s,int size,boolean bold){
    TextView v=new TextView(this);v.setText(s);v.setTextSize(size);v.setTextColor(-1);
    if(bold)v.setTypeface(null,Typeface.BOLD);return v;
  }
  private Button button(String s){
    Button b=new Button(this);b.setAllCaps(false);b.setText(s);b.setTextColor(bg);
    GradientDrawable d=new GradientDrawable();d.setColor(teal);d.setCornerRadius(px(12));b.setBackground(d);
    return b;
  }
  private LinearLayout section(LinearLayout root,String header){
    LinearLayout box=new LinearLayout(this);box.setOrientation(1);box.setPadding(px(16),px(15),px(16),px(16));
    GradientDrawable d=new GradientDrawable();d.setColor(panel);d.setCornerRadius(px(16));box.setBackground(d);
    LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(-1,-2);lp.topMargin=px(17);root.addView(box,lp);
    box.addView(text(header,17,true));return box;
  }
  private void row(LinearLayout parent,View child,int top){
    LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(-1,-2);lp.topMargin=px(top);parent.addView(child,lp);
  }
  @Override public void onCreate(Bundle saved){
    super.onCreate(saved);getWindow().setStatusBarColor(bg);getWindow().setNavigationBarColor(bg);
    // Android 13+ may not display task progress if notifications are denied.
    if(Build.VERSION.SDK_INT>=33 &&
       checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS)!=PackageManager.PERMISSION_GRANTED)
       requestPermissions(new String[]{android.Manifest.permission.POST_NOTIFICATIONS},901);
    ScrollView scroll=new ScrollView(this);scroll.setBackgroundColor(bg);
    LinearLayout root=new LinearLayout(this);root.setOrientation(1);root.setPadding(px(20),px(25),px(20),px(30));
    scroll.addView(root);setContentView(scroll);
    TextView title=text("离线视频 AI 清晰增强",25,true);row(root,title,0);
    TextView hint=text("本地处理 · 无需账号 · 不上传视频",13,false);
    hint.setTextColor(Color.rgb(177,195,212));row(root,hint,7);
    LinearLayout src=section(root,"① 选择视频");
    info=text("尚未选择视频",13,false);row(src,info,10);
    choose=button("从相册选择视频");row(src,choose,13);choose.setOnClickListener(v->pickVideoFromGallery());
    browse=button("从文件管理器选择视频");row(src,browse,8);browse.setOnClickListener(v->pickVideoFromFiles());
    preview=new VideoView(this);LinearLayout.LayoutParams pv=new LinearLayout.LayoutParams(-1,px(175));pv.topMargin=px(8);
    src.addView(preview,pv);preview.setVisibility(View.GONE);
    LinearLayout settings=section(root,"② 选择增强方式");
    modes=new RadioGroup(this);modes.setOrientation(1);
    addMode(10,"清晰优化 · 原始分辨率",true);
    addMode(11,"高清增强 · 最高 1080P",false);
    addMode(12,"超清增强 · 最多 2 倍 / 4K",false);
    addMode(13,"快速 AI · FSRCNN 2 倍（旧版，提升有限）",false);
    addMode(14,"高画质 AI · Real-ESRGAN 2 倍（720P以下，很慢）",false);
    row(settings,modes,8);
    LinearLayout output=section(root,"③ 导出高清 MP4");
    run=button("开始离线处理");row(output,run,8);run.setOnClickListener(v->saveVideo());
    progress=new ProgressBar(this,null,android.R.attr.progressBarStyleHorizontal);
    progress.setMax(100);progress.setProgressTintList(ColorStateList.valueOf(teal));row(output,progress,15);
    status=text("尚未开始",13,false);row(output,status,8);
    stop=button("取消处理");row(output,stop,12);stop.setEnabled(false);
    stop.setOnClickListener(v->{
      ProcessingService.requestCancel(this);
      status.setText("已请求取消，正在安全释放编解码器…");
      stop.setEnabled(false);
    });
    TextView tip=text("v0.6：视频使用前台服务处理，切换应用或锁屏可继续，通知栏显示进度与取消按钮；请允许通知和后台耗电。高画质 AI 非常耗电耗时，首次建议 2～5 秒短片。系统强制停止、内存不足或高温时仍可能中断。",12,false);
    tip.setTextColor(Color.rgb(170,185,202));row(root,tip,16);
  }
  private void addMode(int id,String s,boolean checked) {
    RadioButton b=new RadioButton(this);b.setId(id);b.setText(s);b.setTextColor(-1);b.setTextSize(13);
    b.setButtonTintList(ColorStateList.valueOf(teal));b.setPadding(px(2),px(6),0,px(6));
    modes.addView(b);if(checked)b.setChecked(true);
  }
  /** The system Photo Picker opens a gallery-like video grid on Android 13+. */
  static Intent galleryIntent(boolean photoPickerAvailable) {
    Intent i=photoPickerAvailable?new Intent(MediaStore.ACTION_PICK_IMAGES):new Intent(Intent.ACTION_GET_CONTENT);
    i.setType("video/*");
    if(!photoPickerAvailable)i.addCategory(Intent.CATEGORY_OPENABLE);
    i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
    return i;
  }
  private void pickVideoFromGallery() {
    if(ProcessingService.isWorking())return;
    if(Build.VERSION.SDK_INT>=33) {
      try {
        startActivityForResult(galleryIntent(true),OPEN_GALLERY);
        return;
      }catch(ActivityNotFoundException|SecurityException ignored){}
    }
    try {
      startActivityForResult(Intent.createChooser(galleryIntent(false),"从相册选择视频"),OPEN_GALLERY);
    }catch(ActivityNotFoundException|SecurityException e) {
      status.setText("无法启动相册，请尝试下方的文件管理器入口。");
    }
  }
  private void pickVideoFromFiles() {
    if(ProcessingService.isWorking())return;
    Intent i=new Intent(Intent.ACTION_OPEN_DOCUMENT);
    i.addCategory(Intent.CATEGORY_OPENABLE);i.setType("video/*");
    i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION|Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
    try{startActivityForResult(i,OPEN_FILE);}
    catch(ActivityNotFoundException e){status.setText("系统没有可用的文件管理器。");}
  }
  private void saveVideo() {
    if(ProcessingService.isWorking())return;
    if(video==null){status.setText("请先选择视频。");return;}
    Intent i=new Intent(Intent.ACTION_CREATE_DOCUMENT);i.addCategory(Intent.CATEGORY_OPENABLE);i.setType("video/mp4");
    i.putExtra(Intent.EXTRA_TITLE,"高清增强_"+new SimpleDateFormat("yyyyMMdd_HHmmss",Locale.US).format(new Date())+".mp4");
    i.addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION);startActivityForResult(i,SAVE);
  }
  @Override @SuppressWarnings("deprecation") protected void onActivityResult(int req,int result,Intent data){
    super.onActivityResult(req,result,data);
    if(result!=RESULT_OK||data==null||data.getData()==null)return;
    Uri u=data.getData();
    if(req==OPEN_FILE||req==OPEN_GALLERY) {
      video=u;
      // Photo Picker grants may also be persisted on supported Android versions.
      try{getContentResolver().takePersistableUriPermission(u,Intent.FLAG_GRANT_READ_URI_PERMISSION);}
      catch(Exception ignored){}
      info.setText(describe(u));preview.setVisibility(View.VISIBLE);preview.setVideoURI(u);
      // For eligible clips select the stronger neural engine automatically.
      try {
        MediaMetadataRetriever m=new MediaMetadataRetriever();
        try {
          m.setDataSource(this,u);
          String ws=m.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH);
          String hs=m.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT);
          int vw=Integer.parseInt(ws),vh=Integer.parseInt(hs);
          if(Math.max(vw,vh)<=1280&&Math.min(vw,vh)<=720) {
            modes.check(14);
            status.setText("已选择高画质 Real-ESRGAN；请先处理几秒短视频，可能非常慢。");
          } else {
            modes.check(11);
            status.setText("原视频超过 720P，默认普通增强；高画质 AI 暂不支持。");
          }
        } finally {m.release();}
      } catch(Exception e) {
        modes.check(10);
        status.setText("请选择增强模式后开始导出。");
      }
      progress.setProgress(0);
    } else if(req==SAVE) {
      try{getContentResolver().takePersistableUriPermission(u,
          Intent.FLAG_GRANT_READ_URI_PERMISSION|Intent.FLAG_GRANT_WRITE_URI_PERMISSION);}
      catch(Exception ignored){}
      start(u);
    }
  }
  private String describe(Uri uri) {
    String name="已选择视频";
    try(Cursor c=getContentResolver().query(uri,null,null,null,null)) {
      if(c!=null&&c.moveToFirst()){int col=c.getColumnIndex(OpenableColumns.DISPLAY_NAME);if(col>=0)name=c.getString(col);}
    }catch(Exception ignored){}
    MediaMetadataRetriever m=new MediaMetadataRetriever();
    try {
      m.setDataSource(this,uri);
      String w=m.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH);
      String h=m.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT);
      String d=m.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION);
      if(w!=null&&h!=null&&d!=null)name+="\n"+w+"×"+h+" · "+(Long.parseLong(d)/1000)+" 秒";
    }catch(Exception ignored){}finally{try{m.release();}catch(Exception ignored){}}
    return name;
  }
  private void start(Uri output) {
    if(video==null||ProcessingService.isWorking())return;
    final int selected=modes.getCheckedRadioButtonId();
    final int mode=selected==11?OutputProfile.HD:selected==12?OutputProfile.DOUBLE:
        selected==13?OutputProfile.AI:selected==14?OutputProfile.QUALITY_AI:OutputProfile.NATIVE;
    // Release the preview player to save memory for the native AI runtime.
    preview.stopPlayback();
    preview.setVisibility(View.GONE);
    try {
      ProcessingService.start(this,video,output,mode);
      status.setText("后台任务启动中，可切换应用。通过常驻通知查看进度。");
      progress.setProgress(0);
      choose.setEnabled(false);browse.setEnabled(false);run.setEnabled(false);stop.setEnabled(true);
    }catch(Exception e){
      status.setText("处理任务无法启动："+e.getMessage());
      try{getContentResolver().delete(output,null,null);}catch(Exception ignored){}
    }
  }
  @Override protected void onResume(){
    super.onResume();
    polling=true;
    progressHandler.removeCallbacks(observer);
    progressHandler.post(observer);
    if(!ProcessingService.isWorking() &&
       getSharedPreferences("processing_state",MODE_PRIVATE).getBoolean("unfinished",false)) {
      status.setText("上次处理意外中断，可能被系统终止。请重试，旧输出可能不完整。");
    }
  }
  @Override protected void onPause(){
    polling=false;
    progressHandler.removeCallbacks(observer);
    super.onPause();
  }
  @Override protected void onDestroy(){
    // The foreground service owns the video job. Never cancel it just because an Activity dies.
    if(preview!=null)preview.stopPlayback();
    super.onDestroy();
  }
}
