package com.offline.clearvideo;

import android.app.Activity;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.ClipData;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.net.Uri;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;
import android.util.Log;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * User-initiated, offline local file processing in a foreground service.
 * The Activity can be paused/destroyed while the decoder, neural model and muxer remain active.
 * This does not bypass force-stop, OOM, thermal limits or vendor-specific process termination.
 */
public final class ProcessingService extends Service {
    static final String ACTION_START="com.offline.clearvideo.action.START_PROCESSING";
    static final String ACTION_CANCEL="com.offline.clearvideo.action.CANCEL_PROCESSING";
    private static final int NOTIFICATION_ID=714;
    private static final int FINISHED_NOTIFICATION_ID=715;
    private static final String CHANNEL_ID="offline_video_processing";
    private static final String PREFS="processing_state";

    static final class Snapshot {
        final boolean running;
        final int progress;
        final String message;
        Snapshot(boolean running,int progress,String message){
            this.running=running;this.progress=progress;this.message=message;
        }
    }
    private static volatile Snapshot state=new Snapshot(false,0,"尚未开始");
    private final AtomicBoolean canceled=new AtomicBoolean(false);
    private final Handler main=new Handler(Looper.getMainLooper());
    private volatile Thread worker;
    private volatile long lastNotify;
    private NotificationManager notificationManager;

    static Snapshot snapshot(){return state;}
    static boolean isWorking(){return state.running;}

    /** Must be called while the app is visible (Android 12+ background-start restrictions). */
    static void start(Context context,Uri source,Uri target,int mode) {
        if(state.running)throw new IllegalStateException("已有视频任务正在进行");
        if(source==null||target==null)throw new IllegalArgumentException("输入或输出视频地址为空");
        state=new Snapshot(true,0,"正在启动前台服务…");
        Intent intent=new Intent(context,ProcessingService.class).setAction(ACTION_START);
        intent.putExtra("input",source.toString());
        intent.putExtra("output",target.toString());
        intent.putExtra("mode",mode);
        ClipData clip=ClipData.newRawUri("input-video",source);
        clip.addItem(new ClipData.Item(target));
        intent.setClipData(clip);
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION|Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
        try {context.startForegroundService(intent);}
        catch(RuntimeException failure) {
            state=new Snapshot(false,0,"前台任务无法启动："+failure.getMessage());
            throw failure;
        }
    }
    static void requestCancel(Context context) {
        if(!state.running)return;
        Intent intent=new Intent(context,ProcessingService.class).setAction(ACTION_CANCEL);
        context.startService(intent);
    }

    @Override public void onCreate(){
        super.onCreate();
        notificationManager=(NotificationManager)getSystemService(NOTIFICATION_SERVICE);
        notificationManager.createNotificationChannel(new NotificationChannel(
            CHANNEL_ID,"视频离线增强进度",NotificationManager.IMPORTANCE_LOW));
    }

    @Override public int onStartCommand(Intent intent,int flags,int startId) {
        if(intent==null){stopSelf(startId);return START_NOT_STICKY;}
        if(ACTION_CANCEL.equals(intent.getAction())) {
            canceled.set(true);
            state=new Snapshot(true,state.progress,"正在取消处理并清理输出…");
            notificationManager.notify(NOTIFICATION_ID,notification(true));
            if(worker==null)stopSelf(startId);
            return START_NOT_STICKY;
        }
        if(!ACTION_START.equals(intent.getAction())) {
            stopSelf(startId);return START_NOT_STICKY;
        }
        if(worker!=null&&worker.isAlive())return START_NOT_STICKY;
        final Uri input,output;
        final int mode=intent.getIntExtra("mode",OutputProfile.NATIVE);
        try {
            input=Uri.parse(intent.getStringExtra("input"));
            output=Uri.parse(intent.getStringExtra("output"));
            if(input.equals(output))throw new IllegalArgumentException("输入和输出不能相同");
            // Required before doing work; especially important on Android 8+.
            if(android.os.Build.VERSION.SDK_INT>=29)
                startForeground(NOTIFICATION_ID,notification(false),
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
            else startForeground(NOTIFICATION_ID,notification(false));
        }catch(Exception e) {
            state=new Snapshot(false,0,"服务启动失败："+e.getMessage());
            Log.e("OfflineVideo","Could not start foreground processing",e);
            stopSelf(startId);return START_NOT_STICKY;
        }
        canceled.set(false);
        final String outputText=output.toString();
        getSharedPreferences(PREFS,MODE_PRIVATE).edit()
            .putBoolean("unfinished",true).putString("output_uri",outputText).apply();
        worker=new Thread(()->process(input,output,mode,startId),"offline-video-foreground");
        worker.start();
        return START_NOT_STICKY;
    }

    private void process(Uri input,Uri output,int mode,int startId) {
        boolean success=false;
        String message;
        PowerManager.WakeLock lock=null;
        try {
            PowerManager pm=(PowerManager)getSystemService(POWER_SERVICE);
            if(pm!=null){
                lock=pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK,"OfflineClearVideo:VideoProcessing");
                lock.setReferenceCounted(false);
                lock.acquire(2*60*60*1000L); // safety timeout, released in finally
            }
            VideoProcessor.Result result=VideoProcessor.process(this,input,output,mode,
                (percent,detail)->progress(percent,detail),canceled);
            message="完成："+result.width+"×"+result.height+"，共"+result.frames+"帧"+
                    (result.retainedAudio?"，已保留音轨":result.audioPresent?"，音轨不兼容":"");
            success=true;
        }catch(InterruptedException e){
            message="任务已取消，已清理不完整文件";
        }catch(OutOfMemoryError e){
            Log.e("OfflineVideo","Insufficient heap/native memory",e);
            message="内存不足：请用短视频或快速 AI 模式重试";
        }catch(Throwable e){
            Log.e("OfflineVideo","Background video processing failed",e);
            message="处理失败："+(e.getMessage()==null?e.getClass().getSimpleName():e.getMessage());
        }finally {
            if(lock!=null&&lock.isHeld())try{lock.release();}catch(Exception ignored){}
        }
        if(!success)try{getContentResolver().delete(output,null,null);}catch(Exception ignored){}
        getSharedPreferences(PREFS,MODE_PRIVATE).edit().putBoolean("unfinished",false)
            .remove("output_uri").putString("last_message",message).apply();
        final String lastMessage=message;
        final boolean completed=success;
        state=new Snapshot(false,completed?100:0,lastMessage);
        main.post(()->{
            try {
                stopForeground(STOP_FOREGROUND_REMOVE);
                notificationManager.notify(FINISHED_NOTIFICATION_ID,finishedNotification(completed,lastMessage));
            }catch(Exception e){Log.e("OfflineVideo","Notification cleanup failure",e);}
            stopSelf(startId);
        });
    }

    private void progress(int percent,String message) {
        if(canceled.get())return;
        state=new Snapshot(true,Math.max(0,Math.min(99,percent)),message);
        long now=SystemClock.elapsedRealtime();
        if(now-lastNotify>1400){
            lastNotify=now;
            main.post(()->{
                if(state.running&&!canceled.get())
                    notificationManager.notify(NOTIFICATION_ID,notification(false));
            });
        }
    }

    private Notification notification(boolean canceling) {
        Snapshot s=state;
        Intent open=new Intent(this,MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP|Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent pending=PendingIntent.getActivity(this,0,open,
            PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);
        Notification.Builder b=new Notification.Builder(this,CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher)
            .setContentTitle(canceling?"正在取消视频增强…":"离线 AI 视频增强进行中")
            .setContentText(s.message)
            .setContentIntent(pending)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setProgress(100,s.progress,s.progress==0)
            .setVisibility(Notification.VISIBILITY_PUBLIC);
        if(!canceling){
            Intent cancel=new Intent(this,ProcessingService.class).setAction(ACTION_CANCEL);
            PendingIntent ci=PendingIntent.getService(this,1,cancel,
                PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);
            b.addAction(android.R.drawable.ic_menu_close_clear_cancel,"取消",ci);
        }
        return b.build();
    }
    private Notification finishedNotification(boolean success,String message) {
        Intent open=new Intent(this,MainActivity.class);
        PendingIntent pending=PendingIntent.getActivity(this,2,open,
            PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);
        return new Notification.Builder(this,CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher)
            .setContentTitle(success?"视频处理完成":"视频处理未完成")
            .setContentText(message)
            .setStyle(new Notification.BigTextStyle().bigText(message))
            .setAutoCancel(true).setContentIntent(pending).build();
    }
    @Override public IBinder onBind(Intent intent){return null;}
    @Override public void onDestroy(){
        if(worker!=null&&worker.isAlive())canceled.set(true);
        super.onDestroy();
    }
}
